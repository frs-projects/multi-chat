// One event on the multichat stream. Mirrors the Java side's ChatMessage: flat Redis
// stream fields, no JSON blob. Unknown extra fields are ignored on read.
export interface ChatMessage {
  /** producing endpoint: a server id like "EU_1" or a connector id like "discord" */
  source: string;
  /** "chat" | "join" | "leave" | "death" | "advancement" | "status" | "roster" | custom */
  type: string;
  /** player UUID, empty for non-player events */
  uuid: string;
  name: string;
  content: string;
  /** epoch millis at publish time */
  ts: number;
  /** free-form passthrough for custom publishers */
  meta: string;
}

/** What a connector hands to publish(); source/ts are stamped by the bus. */
export type OutboundMessage = Partial<Omit<ChatMessage, "source" | "ts">> & Pick<ChatMessage, "type">;

/** Alternating field/value strings for XADD; empty optional fields are omitted. */
export function toFields(msg: ChatMessage): string[] {
  const out = ["source", msg.source, "type", msg.type, "ts", String(msg.ts)];
  if (msg.uuid) out.push("uuid", msg.uuid);
  if (msg.name) out.push("name", msg.name);
  if (msg.content) out.push("content", msg.content);
  if (msg.meta) out.push("meta", msg.meta);
  return out;
}

export function fromFields(fields: string[]): ChatMessage {
  const map: Record<string, string> = {};
  for (let i = 0; i + 1 < fields.length; i += 2) map[fields[i]] = fields[i + 1];
  return {
    source: map.source ?? "",
    type: map.type ?? "",
    uuid: map.uuid ?? "",
    name: map.name ?? "",
    content: map.content ?? "",
    ts: Number(map.ts ?? 0) || 0,
    meta: map.meta ?? "",
  };
}
