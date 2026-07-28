import { describe, expect, test } from "bun:test";
import { PresenceTracker } from "./presence.ts";
import type { ChatMessage } from "../../core/types.ts";

function event(source: string, type: string, extra: Partial<ChatMessage> = {}): ChatMessage {
  return { source, type, uuid: "", name: "", content: "", ts: 1, meta: "", ...extra };
}

const join = (source: string, uuid: string) => event(source, "join", { uuid, name: uuid });
const leave = (source: string, uuid: string) => event(source, "leave", { uuid, name: uuid });
const status = (source: string, content: string) => event(source, "status", { content });

describe("presence tracker", () => {
  test("counts joins and leaves per server", () => {
    const p = new PresenceTracker();
    p.apply(status("EU_1", "started"));
    p.apply(status("EU_2", "started"));
    p.apply(join("EU_1", "a"));
    p.apply(join("EU_1", "b"));
    p.apply(join("EU_2", "c"));
    expect(p.counts()).toEqual({ players: 3, servers: 2 });

    p.apply(leave("EU_1", "a"));
    expect(p.counts()).toEqual({ players: 2, servers: 2 });
  });

  test("duplicate joins and unknown leaves don't skew the tally", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_1", "a"));
    expect(p.apply(join("EU_1", "a"))).toBe(false);
    expect(p.apply(leave("EU_1", "ghost"))).toBe(false);
    expect(p.apply(leave("EU_2", "a"))).toBe(false); // same player, other server
    expect(p.counts()).toEqual({ players: 1, servers: 1 });
  });

  test("a join implies its server is online", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_1", "a"));
    expect(p.counts()).toEqual({ players: 1, servers: 1 });
  });

  test("stopping drops the server and its players, other servers unaffected", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_1", "a"));
    p.apply(join("EU_1", "b"));
    p.apply(join("EU_2", "c"));
    p.apply(status("EU_1", "stopping"));
    expect(p.counts()).toEqual({ players: 1, servers: 1 });
  });

  test("started resets a server's players (crash without leave events)", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_1", "a"));
    p.apply(join("EU_1", "b"));
    expect(p.apply(status("EU_1", "started"))).toBe(true);
    expect(p.counts()).toEqual({ players: 0, servers: 1 });
  });

  test("chat and other types leave the counters alone", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_1", "a"));
    expect(p.apply(event("EU_1", "chat", { name: "a", content: "hi" }))).toBe(false);
    expect(p.apply(event("EU_1", "death", { name: "a" }))).toBe(false);
    expect(p.counts()).toEqual({ players: 1, servers: 1 });
  });

  test("falls back to the name when no uuid is present", () => {
    const p = new PresenceTracker();
    p.apply(event("EU_1", "join", { name: "Steve" }));
    expect(p.counts().players).toBe(1);
    p.apply(event("EU_1", "leave", { name: "Steve" }));
    expect(p.counts().players).toBe(0);
  });

  test("render fills the template", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_1", "a"));
    expect(p.render("{players} players on {servers} servers")).toBe("1 players on 1 servers");
  });
});
