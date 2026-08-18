import { describe, expect, test } from "bun:test";
import { neutralizeMentions } from "./rest.ts";

const ZWSP = "​";

describe("neutralizeMentions", () => {
  test("breaks the mass-ping keywords in any casing", () => {
    expect(neutralizeMentions("hey @everyone look")).toBe(`hey @${ZWSP}everyone look`);
    expect(neutralizeMentions("@here")).toBe(`@${ZWSP}here`);
    expect(neutralizeMentions("@EveryOne")).toBe(`@${ZWSP}EveryOne`);
  });

  test("breaks raw user and role mention syntax", () => {
    expect(neutralizeMentions("<@1234>")).toBe(`<${ZWSP}@1234>`);
    expect(neutralizeMentions("<@!1234>")).toBe(`<${ZWSP}@!1234>`);
    expect(neutralizeMentions("<@&1234>")).toBe(`<${ZWSP}@&1234>`);
  });

  test("leaves ordinary chat alone", () => {
    expect(neutralizeMentions("email me at steve@example.com")).toBe("email me at steve@example.com");
    expect(neutralizeMentions("built a house #42")).toBe("built a house #42");
    expect(neutralizeMentions("")).toBe("");
  });
});
