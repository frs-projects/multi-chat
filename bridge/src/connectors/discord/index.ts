import type { Connector, ConnectorCtx } from "../../core/connector.ts";
import type { ChatMessage } from "../../core/types.ts";
import type { ChannelRule, DiscordConfig } from "../../core/config.ts";
import { format, matches, stripColors } from "../../core/router.ts";
import { DiscordGateway, type GatewayMessageEvent } from "./gateway.ts";
import { DiscordRest } from "./rest.ts";

// Discord connector: gateway for inbound (discord -> stream), webhook-first for
// outbound (stream -> discord, falls back to bot messages when no webhook is set).

/** Webhook URLs look like .../api/webhooks/<id>/<token>; the id shows up as webhook_id on MESSAGE_CREATE. */
function webhookId(url: string | undefined): string | undefined {
  return url?.match(/\/webhooks\/(\d+)\//)?.[1];
}

export function createDiscordConnector(cfg: DiscordConfig, log: (...args: unknown[]) => void): Connector {
  const rest = new DiscordRest(log);
  const ownWebhookIds = new Set(cfg.channels.map((c) => webhookId(c.webhook)).filter(Boolean));
  let gateway: DiscordGateway | null = null;

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
      } else {
        log("no DISCORD_TOKEN set - running outbound-only (webhooks), no messages read from Discord");
      }
    },

    async stop() {
      gateway?.stop();
    },

    async deliver(msg: ChatMessage) {
      for (const rule of cfg.channels) {
        if (matches(rule, msg)) {
          await deliverTo(rule, msg);
        }
      }
    },
  };
}
