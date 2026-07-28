import type { ChatMessage } from "./types.ts";

// Pure routing/formatting helpers, shared by all connectors.

/** True when the list allows the value: empty or containing "*" allows everything. */
export function allows(list: string[] | undefined, value: string): boolean {
  return !list || list.length === 0 || list.includes("*") || list.includes(value);
}

/** Type + source filter used by per-channel routing rules. */
export function matches(rule: { types?: string[]; sources?: string[] }, msg: ChatMessage): boolean {
  return allows(rule.types, msg.type) && allows(rule.sources, msg.source);
}

/** Fills {source} {name} {message} {type} {uuid} placeholders. */
export function format(template: string, msg: ChatMessage): string {
  return template
    .replaceAll("{source}", msg.source)
    .replaceAll("{name}", msg.name)
    .replaceAll("{message}", msg.content)
    .replaceAll("{type}", msg.type)
    .replaceAll("{uuid}", msg.uuid);
}

/** Removes Minecraft §-style formatting codes (they mean nothing outside the game). */
export function stripColors(text: string): string {
  return text.replace(/§./g, "");
}
