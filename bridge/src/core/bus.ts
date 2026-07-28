import { RedisClient } from "bun";
import type { Connector } from "./connector.ts";
import { fromFields, toFields, type ChatMessage, type OutboundMessage } from "./types.ts";

// Redis Streams access via Bun's built-in RedisClient and its raw send() escape hatch.
//
// Spike findings (tools/spike-streams.ts, Bun 1.3.3): XADD/XGROUP/XREADGROUP/XACK all
// work through send(); XREADGROUP replies arrive RESP3-shaped as a plain object
// { [streamKey]: [ [id, [f1, v1, ...]], ... ] } and a timed-out BLOCK resolves to null.
// A blocked reader does NOT stall other clients, but auto-pipelining on a single
// connection would queue commands behind the BLOCK - hence one dedicated reader
// connection per connector and a shared writer for XADD/XACK.

const BLOCK_MS = 5000;
const BACKOFF_START_MS = 1000;
const BACKOFF_CAP_MS = 30_000;

export interface BusOptions {
  url: string;
  streamKey: string;
  maxStreamLength: number;
  maxCatchupAgeMs: number;
}

type ReadReply = Record<string, [string, string[] | null][]> | null;

export class StreamBus {
  private readonly opts: BusOptions;
  private readonly writer: RedisClient;
  private readonly loops: Promise<void>[] = [];
  private running = true;

  constructor(opts: BusOptions) {
    this.opts = opts;
    this.writer = new RedisClient(opts.url);
  }

  /** Stamps source + ts and XADDs with approximate MAXLEN trimming. */
  async publish(sourceId: string, msg: OutboundMessage): Promise<void> {
    const full: ChatMessage = {
      source: sourceId,
      type: msg.type,
      uuid: msg.uuid ?? "",
      name: msg.name ?? "",
      content: msg.content ?? "",
      ts: Date.now(),
      meta: msg.meta ?? "",
    };
    await this.writer.send("XADD", [
      this.opts.streamKey, "MAXLEN", "~", String(this.opts.maxStreamLength), "*",
      ...toFields(full),
    ]);
  }

  /** Starts the blocking consume loop for one connector (group bridge:<id>). */
  attach(connector: Connector, log: (...args: unknown[]) => void): void {
    this.loops.push(this.consumeLoop(connector, log));
  }

  async stop(): Promise<void> {
    this.running = false;
    // Readers notice within one BLOCK interval; don't hold shutdown longer than that.
    await Promise.race([Promise.allSettled(this.loops), Bun.sleep(BLOCK_MS + 1000)]);
    this.writer.close();
  }

  private async consumeLoop(connector: Connector, log: (...args: unknown[]) => void): Promise<void> {
    const group = `bridge:${connector.id}`;
    const consumer = "main";
    let backoff = BACKOFF_START_MS;
    while (this.running) {
      const reader = new RedisClient(this.opts.url);
      try {
        try {
          await reader.send("XGROUP", ["CREATE", this.opts.streamKey, group, "$", "MKSTREAM"]);
        } catch (e) {
          if (!String(e).includes("BUSYGROUP")) throw e;
        }
        log(`consumer connected, group=${group} stream=${this.opts.streamKey}`);
        backoff = BACKOFF_START_MS;

        // Recover entries delivered but not ACKed before a crash (explicit id, never blocks).
        while (this.running) {
          const pending = (await reader.send("XREADGROUP", [
            "GROUP", group, consumer, "COUNT", "100", "STREAMS", this.opts.streamKey, "0",
          ])) as ReadReply;
          if (!(await this.handleReply(pending, connector, group, log))) break;
        }

        while (this.running) {
          const reply = (await reader.send("XREADGROUP", [
            "GROUP", group, consumer, "COUNT", "32", "BLOCK", String(BLOCK_MS),
            "STREAMS", this.opts.streamKey, ">",
          ])) as ReadReply;
          await this.handleReply(reply, connector, group, log);
        }
      } catch (e) {
        if (!this.running) break;
        log(`consumer ${group} lost Redis (${(e as Error).message}), retrying in ${backoff}ms`);
        await Bun.sleep(backoff);
        backoff = Math.min(backoff * 2, BACKOFF_CAP_MS);
      } finally {
        reader.close();
      }
    }
  }

  /** Delivers + ACKs every entry in a read reply; returns how many entries it saw. */
  private async handleReply(
    reply: ReadReply,
    connector: Connector,
    group: string,
    log: (...args: unknown[]) => void,
  ): Promise<number> {
    if (!reply) return 0;
    let count = 0;
    for (const entries of Object.values(reply)) {
      for (const [id, fields] of entries) {
        count++;
        // Pending entries whose data was trimmed away come back with null fields.
        if (fields) {
          const msg = fromFields(fields);
          const stale =
            this.opts.maxCatchupAgeMs > 0 && msg.ts < Date.now() - this.opts.maxCatchupAgeMs;
          if (msg.source !== connector.id && !stale) {
            try {
              await connector.deliver(msg);
            } catch (e) {
              log(`connector ${connector.id} failed to deliver ${msg.type} from ${msg.source}:`, e);
            }
          }
        }
        await this.writer.send("XACK", [this.opts.streamKey, group, id]);
      }
    }
    return count;
  }
}
