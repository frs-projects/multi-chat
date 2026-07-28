import type { ChatMessage, OutboundMessage } from "./types.ts";

// The platform SPI. Adding a platform = one folder under src/connectors/ exporting a
// factory that returns this interface, plus a [connectors.<id>] block in the config.

export interface ConnectorCtx {
  /** Publish an event from this platform onto the stream; source + ts are stamped by the bus. */
  publish(msg: OutboundMessage): Promise<void>;
  log(...args: unknown[]): void;
}

export interface Connector {
  /** Consumer-group suffix (bridge:<id>) and the source id stamped on published messages. */
  readonly id: string;
  start(ctx: ConnectorCtx): Promise<void>;
  stop(): Promise<void>;
  /** Deliver a stream message (already filtered for own-source/staleness) to the platform. */
  deliver(msg: ChatMessage): Promise<void>;
}
