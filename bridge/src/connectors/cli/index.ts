import type { Connector, ConnectorCtx } from "../../core/connector.ts";
import type { ChatMessage } from "../../core/types.ts";
import type { CliConfig } from "../../core/config.ts";

// Dev/test connector: stream messages print to stdout, lines typed on stdin publish as
// chat. Lets the whole pipeline (mod <-> redis <-> bridge) be exercised without Discord.

export function createCliConnector(cfg: CliConfig, log: (...args: unknown[]) => void): Connector {
  let running = true;
  return {
    id: cfg.sourceId,

    async start(ctx: ConnectorCtx) {
      log("cli connector ready - type a line to publish it as chat from", cfg.sourceId);
      (async () => {
        for await (const line of console) {
          if (!running) break;
          const text = line.trim();
          if (text) await ctx.publish({ type: "chat", name: "console", content: text });
        }
      })().catch((e) => log("cli stdin reader stopped:", e));
    },

    async stop() {
      running = false;
    },

    async deliver(msg: ChatMessage) {
      const who = msg.name ? ` ${msg.name}:` : "";
      console.log(`[${msg.source}] (${msg.type})${who} ${msg.content}`);
    },
  };
}
