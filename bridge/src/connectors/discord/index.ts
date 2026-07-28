import type { Connector, ConnectorCtx } from "../../core/connector.ts";
import type { ChatMessage } from "../../core/types.ts";
import type { ChannelRule, DiscordConfig } from "../../core/config.ts";
import { format, matches, stripColors } from "../../core/router.ts";
import { DiscordGateway, type GatewayMessageEvent } from "./gateway.ts";
import { DiscordRest } from "./rest.ts";
import { PresenceTracker } from "./presence.ts";

// Discord connector: gateway for inbound (discord -> stream), webhook-first for
// outbound (stream -> discord, falls back to bot messages when no webhook is set).
// Every delivered event also feeds the presence counters shown as the bot's status.

/** Discord allows 5 presence updates per 20s per session; one per 15s stays well clear. */
const PRESENCE_MIN_INTERVAL_MS = 15_000;

/**
 * Rosters are telemetry for the bot status, not chat: relaying them would post a player
 * list to every channel every few seconds. A channel has to name the type explicitly
 * (DISCORD_CHANNEL_n_TYPES=roster) to get them - a "*" filter is not enough.
 */
function isSilentTelemetry(rule: ChannelRule, msg: ChatMessage): boolean {
  return msg.type === "roster" && !rule.types?.includes("roster");
}

/** Webhook URLs look like .../api/webhooks/<id>/<token>; the id shows up as webhook_id on MESSAGE_CREATE. */
function webhookId(url: string | undefined): string | undefined {
  return url?.match(/\/webhooks\/(\d+)\//)?.[1];
}

export function createDiscordConnector(cfg: DiscordConfig, log: (...args: unknown[]) => void): Connector {
  const rest = new DiscordRest(log);
  const ownWebhookIds = new Set(cfg.channels.map((c) => webhookId(c.webhook)).filter(Boolean));
  let gateway: DiscordGateway | null = null;

  // Presence needs a bot session; webhook-only setups have no status to update.
  const presence = cfg.presence.enabled && cfg.token ? new PresenceTracker() : null;
  let presenceTimer: ReturnType<typeof setTimeout> | null = null;
  let expiryTimer: ReturnType<typeof setInterval> | null = null;
  let lastPresenceAt = 0;

  /** Coalesces bursts of events (a restart re-joining everyone) into one throttled push. */
  function schedulePresenceUpdate(): void {
    if (!presence || presenceTimer) return;
    const wait = Math.max(0, PRESENCE_MIN_INTERVAL_MS - (Date.now() - lastPresenceAt));
    presenceTimer = setTimeout(() => {
      presenceTimer = null;
      lastPresenceAt = Date.now();
      const text = presence.render(cfg.presence.template);
      gateway?.setPresence(
        cfg.presence.activityType === 4
          ? { name: "Custom Status", type: 4, state: text }
          : { name: text, type: cfg.presence.activityType },
      );
    }, wait);
  }

  function onDiscordMessage(ctx: ConnectorCtx, m: GatewayMessageEvent): void {
    const rule = cfg.channels.find((r) => r.channel === m.channel_id && r.inbound !== false);
    if (!rule) return;
    if (m.webhook_id && ownWebhookIds.has(m.webhook_id)) return; // our own relay, avoid the echo loop
    if (cfg.ignoreBots && m.author?.bot) return;
    if (!m.content) return; // embeds/attachments only, or MESSAGE CONTENT intent missing
    const name = m.member?.nick ?? m.author?.global_name ?? m.author?.username ?? "unknown";
    ctx.publish({ type: "chat", name, content: m.content, meta: m.channel_id })
      .catch((e) => log("failed to publish discord message:", e));
  }

  async function deliverTo(rule: ChannelRule, msg: ChatMessage): Promise<void> {
    if (rule.webhook) {
      if (msg.type === "chat" && msg.name) {
        // Chat gets a per-player identity; everything else uses the format templates.
        await rest.sendWebhook(rule.webhook, {
          content: stripColors(msg.content),
          username: `${msg.name} @ ${msg.source}`,
          avatar_url: msg.uuid ? `https://mc-heads.net/avatar/${msg.uuid}` : undefined,
          allowed_mentions: { parse: [] },
        });
      } else {
        const template = cfg.formats[msg.type] ?? cfg.formats.default;
        await rest.sendWebhook(rule.webhook, {
          content: stripColors(format(template, msg)),
          allowed_mentions: { parse: [] },
        });
      }
    } else if (cfg.token && rule.channel) {
      const template = cfg.formats[msg.type] ?? cfg.formats.default;
      await rest.sendChannelMessage(cfg.token, rule.channel, stripColors(format(template, msg)));
    }
  }

  return {
    id: cfg.sourceId,

    async start(ctx: ConnectorCtx) {
      if (cfg.token) {
        gateway = new DiscordGateway(cfg.token, (m) => onDiscordMessage(ctx, m), log);
        gateway.start();
        // Counters start at zero and fill in as events arrive; show that rather than nothing.
        schedulePresenceUpdate();
        if (presence && cfg.presence.rosterTtlMs > 0) {
          // Nothing arrives when a server dies mid-run, so the sweep has to be on a timer.
          expiryTimer = setInterval(() => {
            if (presence.expire(cfg.presence.rosterTtlMs)) schedulePresenceUpdate();
          }, Math.max(5000, Math.floor(cfg.presence.rosterTtlMs / 4)));
        }
      } else {
        log("no DISCORD_TOKEN set - running outbound-only (webhooks), no messages read from Discord");
      }
    },

    async stop() {
      if (presenceTimer) clearTimeout(presenceTimer);
      if (expiryTimer) clearInterval(expiryTimer);
      presenceTimer = null;
      expiryTimer = null;
      gateway?.stop();
    },

    async deliver(msg: ChatMessage) {
      if (presence?.apply(msg)) schedulePresenceUpdate();
      for (const rule of cfg.channels) {
        if (matches(rule, msg) && !isSilentTelemetry(rule, msg)) {
          await deliverTo(rule, msg);
        }
      }
    },
  };
}
