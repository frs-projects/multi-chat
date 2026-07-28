// Configuration comes exclusively from environment variables (see .env.example for the
// full reference) - no config file, so deploying/reconfiguring the stack never means
// editing files, just env.
//
// Channel routing rules are numbered variable groups, first gap ends the list:
//   DISCORD_CHANNEL_1_ID / _WEBHOOK / _TYPES / _SOURCES / _INBOUND
//   DISCORD_CHANNEL_2_ID / ...
// The discord connector is active as soon as at least one channel group is defined.

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

function env(name: string, fallback = ""): string {
  return Bun.env[name]?.trim() || fallback;
}

function envBool(name: string, fallback: boolean): boolean {
  const value = env(name).toLowerCase();
  return value ? ["1", "true", "yes", "on"].includes(value) : fallback;
}

function envNum(name: string, fallback: number): number {
  const value = Number(env(name));
  return Number.isFinite(value) && env(name) !== "" ? value : fallback;
}

/** Comma-separated list; unset/empty means "no filter" (allow everything). */
function envList(name: string): string[] | undefined {
  const value = env(name);
  if (!value) return undefined;
  return value.split(",").map((s) => s.trim()).filter(Boolean);
}

function discordChannels(): ChannelRule[] {
  const rules: ChannelRule[] = [];
  for (let n = 1; ; n++) {
    const channel = env(`DISCORD_CHANNEL_${n}_ID`);
    const webhook = env(`DISCORD_CHANNEL_${n}_WEBHOOK`);
    if (!channel && !webhook) break;
    rules.push({
      channel: channel || undefined,
      webhook: webhook || undefined,
      types: envList(`DISCORD_CHANNEL_${n}_TYPES`),
      sources: envList(`DISCORD_CHANNEL_${n}_SOURCES`),
      inbound: envBool(`DISCORD_CHANNEL_${n}_INBOUND`, true),
    });
  }
  return rules;
}

function discordFormats(): Record<string, string> {
  const formats = { ...DISCORD_DEFAULT_FORMATS };
  for (const key of Object.keys(DISCORD_DEFAULT_FORMATS)) {
    const override = env(`DISCORD_FORMAT_${key.toUpperCase()}`);
    if (override) formats[key] = override;
  }
  return formats;
}

export function loadConfig(): BridgeConfig {
  const cfg: BridgeConfig = {
    redis: {
      url: env("REDIS_URL", "redis://127.0.0.1:6379"),
      streamKey: env("REDIS_STREAM_KEY", "multichat:events"),
      maxStreamLength: envNum("REDIS_MAX_STREAM_LENGTH", 10000),
      maxCatchupAgeSeconds: envNum("MAX_CATCHUP_AGE_SECONDS", 300),
    },
  };

  const channels = discordChannels();
  if (channels.length > 0) {
    cfg.discord = {
      enabled: true,
      sourceId: env("DISCORD_SOURCE_ID", "discord"),
      ignoreBots: envBool("DISCORD_IGNORE_BOTS", true),
      token: env("DISCORD_TOKEN"),
      channels,
      formats: discordFormats(),
    };
  }

  if (envBool("CLI_ENABLED", false)) {
    cfg.cli = { enabled: true, sourceId: env("CLI_SOURCE_ID", "cli") };
  }

  return cfg;
}
