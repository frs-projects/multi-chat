// multichat-bridge - links the multichat Redis stream to external platforms.
//
// The Minecraft-side Link-Mod and this bridge share one Redis stream (default
// "multichat:events") of flat field/value entries:
//   source  producing endpoint id ("EU_1", "discord", ...)
//   type    chat | join | leave | death | advancement | status | <custom via mod API>
//   uuid    player uuid (empty for non-player events)
//   name    display name
//   content plain-text payload
//   ts      epoch millis
//   meta    free-form passthrough
//
// Every endpoint consumes through its own consumer group (this bridge: "bridge:<id>")
// and skips entries whose source is its own id - that is the entire echo-loop guard.
//
// Connectors are pluggable (src/connectors/*): each gets its own consumer loop, so a
// slow platform never blocks another. Ships with:
//   discord  two-way bridge (gateway inbound, webhook/bot outbound)
//   cli      stdin/stdout test connector
//
// Config: environment variables only - see .env.example for the full reference.
// The discord connector activates when at least one DISCORD_CHANNEL_<n> group is set,
// the cli connector via CLI_ENABLED=true.
//
// Run: bun run bridge.ts

import { loadConfig } from "./src/core/config.ts";
import { StreamBus } from "./src/core/bus.ts";
import type { Connector } from "./src/core/connector.ts";
import { createDiscordConnector } from "./src/connectors/discord/index.ts";
import { createCliConnector } from "./src/connectors/cli/index.ts";

const log = (...args: unknown[]) => console.log(new Date().toISOString(), "-", ...args);

const cfg = loadConfig();

const connectors: Connector[] = [];
if (cfg.discord) connectors.push(createDiscordConnector(cfg.discord, (...a) => log("[discord]", ...a)));
if (cfg.cli) connectors.push(createCliConnector(cfg.cli, (...a) => log("[cli]", ...a)));

if (connectors.length === 0) {
  console.error("No connectors configured - nothing to do. See .env.example (DISCORD_CHANNEL_1_* or CLI_ENABLED)");
  process.exit(1);
}

const bus = new StreamBus({
  url: cfg.redis.url,
  streamKey: cfg.redis.streamKey,
  maxStreamLength: cfg.redis.maxStreamLength,
  maxCatchupAgeMs: cfg.redis.maxCatchupAgeSeconds * 1000,
});

for (const connector of connectors) {
  await connector.start({
    publish: (msg) => bus.publish(connector.id, msg),
    log: (...a) => log(`[${connector.id}]`, ...a),
  });
  bus.attach(connector, (...a) => log(`[${connector.id}]`, ...a));
}
log(`bridge running with connector(s): ${connectors.map((c) => c.id).join(", ")}`);

let stopping = false;
async function shutdown(signal: string): Promise<void> {
  if (stopping) return;
  stopping = true;
  log(`${signal} received, shutting down`);
  for (const connector of connectors) await connector.stop().catch(() => {});
  await bus.stop();
  process.exit(0);
}
process.on("SIGINT", () => void shutdown("SIGINT"));
process.on("SIGTERM", () => void shutdown("SIGTERM"));
