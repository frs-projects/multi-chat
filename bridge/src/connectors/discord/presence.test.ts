import { describe, expect, test } from "bun:test";
import { PresenceTracker } from "./presence.ts";
import type { ChatMessage } from "../../core/types.ts";

function event(source: string, type: string, extra: Partial<ChatMessage> = {}): ChatMessage {
  return { source, type, uuid: "", name: "", content: "", ts: 1, meta: "", ...extra };
}

const join = (source: string, name: string) => event(source, "join", { name, uuid: `uuid-${name}` });
const leave = (source: string, name: string) => event(source, "leave", { name, uuid: `uuid-${name}` });
const status = (source: string, content: string) => event(source, "status", { content });
const roster = (source: string, names: string[], count = names.length) =>
  event(source, "roster", { content: names.join(","), meta: String(count) });

describe("presence tracker - join/leave tally", () => {
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

  test("falls back to the uuid when no name is present", () => {
    const p = new PresenceTracker();
    p.apply(event("EU_1", "join", { uuid: "uuid-1" }));
    expect(p.counts().players).toBe(1);
    p.apply(event("EU_1", "leave", { uuid: "uuid-1" }));
    expect(p.counts().players).toBe(0);
  });
});

describe("presence tracker - rosters", () => {
  test("a roster replaces the tally and marks the server online", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a", "b", "c"]));
    expect(p.counts()).toEqual({ players: 3, servers: 1 });
  });

  test("a roster corrects a drifted tally", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_1", "a"));
    p.apply(join("EU_1", "ghost")); // never left, e.g. its leave event was lost
    expect(p.counts().players).toBe(2);
    expect(p.apply(roster("EU_1", ["a"]))).toBe(true);
    expect(p.counts().players).toBe(1);
  });

  test("joins and leaves stay live between rosters", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a", "b"]));
    p.apply(join("EU_1", "c"));
    expect(p.counts().players).toBe(3);
    p.apply(leave("EU_1", "a"));
    expect(p.counts().players).toBe(2);
    // ... and the next roster is authoritative again.
    p.apply(roster("EU_1", ["b", "c"]));
    expect(p.counts().players).toBe(2);
  });

  test("an empty roster empties the server without taking it offline", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a"]));
    p.apply(roster("EU_1", []));
    expect(p.counts()).toEqual({ players: 0, servers: 1 });
  });

  test("a capped name list still reports the exact count from meta", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a", "b"], 250)); // names capped by the mod, meta exact
    expect(p.counts().players).toBe(250);
    p.apply(join("EU_1", "c"));
    expect(p.counts().players).toBe(251);
    p.apply(leave("EU_1", "a"));
    expect(p.counts().players).toBe(250);
  });

  test("stopping still wins over a fresh roster", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a", "b"]));
    p.apply(status("EU_1", "stopping"));
    expect(p.counts()).toEqual({ players: 0, servers: 0 });
  });

  test("rosters and tally-only servers add up side by side", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a", "b"]));
    p.apply(join("EU_2", "c")); // no rosters from this one
    expect(p.counts()).toEqual({ players: 3, servers: 2 });
  });
});

describe("presence tracker - roster expiry", () => {
  test("a server whose rosters stop arriving is dropped", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a", "b"]), 1000);
    expect(p.expire(120_000, 100_000)).toBe(false);
    expect(p.expire(120_000, 200_000)).toBe(true);
    expect(p.counts()).toEqual({ players: 0, servers: 0 });
  });

  test("a live roster keeps the server alive", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a"]), 1000);
    p.apply(roster("EU_1", ["a"]), 150_000);
    expect(p.expire(120_000, 200_000)).toBe(false);
    expect(p.counts()).toEqual({ players: 1, servers: 1 });
  });

  test("servers that never send rosters are never expired", () => {
    const p = new PresenceTracker();
    p.apply(join("EU_2", "c"), 1000);
    expect(p.expire(120_000, 10_000_000)).toBe(false);
    expect(p.counts()).toEqual({ players: 1, servers: 1 });
  });
});

describe("presence tracker - rendering", () => {
  test("render fills the template", () => {
    const p = new PresenceTracker();
    p.apply(roster("EU_1", ["a"]));
    expect(p.render("{players} players on {servers} servers")).toBe("1 players on 1 servers");
  });
});
