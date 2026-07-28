import { resolve } from "node:path";

// Config loading: one TOML file (Bun parses TOML imports natively) plus env overrides
// for secrets so tokens never need to live in the file:
//   REDIS_URL, DISCORD_TOKEN, CONFIG (path to the toml, default ./multichat-bridge.toml)

export interface ChannelRule {
  /** Discord channel id read via the gateway (inbound) and/or bot-message target. */
  channel?: string;
  /** Webhook URL for outbound delivery (nicer name/avatar than bot messages). */
  webhook?: string;
  /** Message types forwarded to this channel; empty or ["*"] = all. */
  types?: string[];
  /** Source ids forwarded to this channel; empty or ["*"] = all. */
  sources?: string[];
  /** Whether messages typed in this channel are published onto the stream. Default true. */
  inbound?: boolean;
}

export interface DiscordConfig {
  enabled: boolean;
  sourceId: string;
  ignoreBots: boolean;
  token: string;
  channels: ChannelRule[];
  formats: Record<string, string>;
}

export interface CliConfig {
  enabled: boolean;
  sourceId: string;
}

export interface BridgeConfig {
  redis: {
    url: string;
    streamKey: string;
    maxStreamLength: number;
    maxCatchupAgeSeconds: number;
  };
  discord?: DiscordConfig;
  cli?: CliConfig;
}

export const DISCORD_DEFAULT_FORMATS: Record<string, string> = {
  chat: "**[{source}]** {name}: {message}",
  join: "*[{source}] {name} joined the game*",
  leave: "*[{source}] {name} left the game*",
  death: "*[{source}] {message}*",
  advancement: "*[{source}] {message}*",
  status: "**[{source}] server {message}**",
  default: "**[{source}]** {name}: {message}",
};

export async function loadConfig(path: string): Promise<BridgeConfig> {
  const absolute = resolve(path);
  const raw = (await import(absolute, { with: { type: "toml" } })).default as Record<string, any>;

  const redis = raw.redis ?? {};
  const cfg: BridgeConfig = {
    redis: {
      url: Bun.env.REDIS_URL ?? redis.url ?? "redis://127.0.0.1:6379",
      streamKey: redis.streamKey ?? "multichat:events",
      maxStreamLength: Number(redis.maxStreamLength ?? 10000),
      maxCatchupAgeSeconds: Number(redis.maxCatchupAgeSeconds ?? 300),
    },
  };

  const discord = raw.connectors?.discord;
  if (discord?.enabled) {
    cfg.discord = {
      enabled: true,
      sourceId: discord.sourceId ?? "discord",
      ignoreBots: discord.ignoreBots ?? true,
      token: Bun.env.DISCORD_TOKEN ?? discord.token ?? "",
      channels: (discord.channels ?? []) as ChannelRule[],
      formats: { ...DISCORD_DEFAULT_FORMATS, ...(discord.formats ?? {}) },
    };
  }

  const cli = raw.connectors?.cli;
  if (cli?.enabled) {
    cfg.cli = { enabled: true, sourceId: cli.sourceId ?? "cli" };
  }

  return cfg;
}
