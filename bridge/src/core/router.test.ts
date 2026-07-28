import { describe, expect, test } from "bun:test";
import { allows, format, matches, stripColors } from "./router.ts";
import { fromFields, toFields, type ChatMessage } from "./types.ts";

const msg: ChatMessage = {
  source: "EU_1",
  type: "chat",
  uuid: "uuid-1",
  name: "Steve",
  content: "hello §cthere",
  ts: 1234,
  meta: "",
};

describe("router", () => {
  test("empty or * lists allow everything", () => {
    expect(allows(undefined, "chat")).toBe(true);
    expect(allows([], "chat")).toBe(true);
    expect(allows(["*"], "anything")).toBe(true);
    expect(allows(["chat", "join"], "chat")).toBe(true);
    expect(allows(["chat", "join"], "death")).toBe(false);
  });

  test("matches combines type and source filters", () => {
    expect(matches({}, msg)).toBe(true);
    expect(matches({ types: ["chat"], sources: ["EU_1"] }, msg)).toBe(true);
    expect(matches({ types: ["death"] }, msg)).toBe(false);
    expect(matches({ sources: ["EU_2"] }, msg)).toBe(false);
  });

  test("format fills placeholders", () => {
    expect(format("[{source}] {name}: {message}", msg)).toBe("[EU_1] Steve: hello §cthere");
  });

  test("stripColors removes § codes", () => {
    expect(stripColors("hello §cthere §r!")).toBe("hello there !");
  });
});

describe("stream fields", () => {
  test("round-trips through toFields/fromFields", () => {
    expect(fromFields(toFields(msg))).toEqual(msg);
  });

  test("omits empty optional fields, defaults on read", () => {
    const status: ChatMessage = { source: "EU_1", type: "status", uuid: "", name: "", content: "started", ts: 1, meta: "" };
    const fields = toFields(status);
    expect(fields).not.toContain("uuid");
    expect(fromFields(fields)).toEqual(status);
  });
});
