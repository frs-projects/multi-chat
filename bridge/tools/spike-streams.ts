// Validation spike: can Bun's built-in RedisClient drive Redis Streams, including a
// blocking XREADGROUP, through its raw send() escape hatch? Run against a local Redis:
//   bun run tools/spike-streams.ts
// Findings are recorded in src/core/bus.ts.
import { RedisClient } from "bun";

const url = process.env.REDIS_URL ?? "redis://127.0.0.1:6379";
const KEY = "multichat:spike";
const GROUP = "bridge:spike";

const writer = new RedisClient(url);
const reader = new RedisClient(url);

console.log("bun", Bun.version);

// group bootstrap (BUSYGROUP tolerated)
try {
  console.log("XGROUP CREATE:", await writer.send("XGROUP", ["CREATE", KEY, GROUP, "$", "MKSTREAM"]));
} catch (e) {
  console.log("XGROUP CREATE threw:", (e as Error).message);
}

console.log("XADD:", await writer.send("XADD", [KEY, "MAXLEN", "~", "100", "*", "source", "EU_1", "type", "chat", "name", "Steve", "content", "hello", "ts", String(Date.now())]));

// non-blocking group read of new entries
const r1 = await reader.send("XREADGROUP", ["GROUP", GROUP, "spike", "COUNT", "10", "STREAMS", KEY, ">"]);
console.log("XREADGROUP > reply:", JSON.stringify(r1, null, 2));

// pending re-read (explicit id 0)
const r2 = await reader.send("XREADGROUP", ["GROUP", GROUP, "spike", "COUNT", "10", "STREAMS", KEY, "0"]);
console.log("XREADGROUP 0 (pending) reply:", JSON.stringify(r2));

// ACK the pending entry, if we can find its id in whatever shape came back
const flat = JSON.stringify(r2).match(/\d+-\d+/);
if (flat) {
  console.log("XACK:", await reader.send("XACK", [KEY, GROUP, flat[0]]));
}

// blocking read with nothing to deliver: does it block ~1500ms and return null?
const t0 = Date.now();
const r3 = await reader.send("XREADGROUP", ["GROUP", GROUP, "spike", "COUNT", "10", "BLOCK", "1500", "STREAMS", KEY, ">"]);
console.log(`XREADGROUP BLOCK timeout after ${Date.now() - t0}ms, reply:`, JSON.stringify(r3));

// blocking read that gets woken by a concurrent XADD on the other connection
const t1 = Date.now();
const pending = reader.send("XREADGROUP", ["GROUP", GROUP, "spike", "COUNT", "10", "BLOCK", "5000", "STREAMS", KEY, ">"]);
setTimeout(() => writer.send("XADD", [KEY, "*", "source", "EU_2", "type", "chat", "content", "wake up"]), 300);
const r4 = await pending;
console.log(`XREADGROUP BLOCK woken after ${Date.now() - t1}ms, reply:`, JSON.stringify(r4));

// does the writer stay usable while the reader blocks? (auto-pipelining check)
const t2 = Date.now();
const blocked = reader.send("XREADGROUP", ["GROUP", GROUP, "spike", "BLOCK", "2000", "STREAMS", KEY, ">"]);
const pong = await writer.send("PING", []);
console.log(`writer PING while reader blocked: ${pong} after ${Date.now() - t2}ms`);
await blocked;

await writer.send("DEL", [KEY]);
writer.close();
reader.close();
console.log("SPIKE DONE");
