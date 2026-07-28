// Minimal Discord Gateway v10 client over Bun's native WebSocket - just enough for a
// chat bridge: identify, heartbeat/ack, resume, MESSAGE_CREATE dispatch. Hand-rolled
// instead of discord.js to keep the bridge dependency-free and tiny.
//
// NOTE: reading message text requires the privileged MESSAGE CONTENT intent, which must
// be enabled for the bot in the Discord developer portal - without it, MESSAGE_CREATE
// arrives with an empty `content` and inbound bridging silently does nothing.

const GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
const INTENTS = (1 << 0) | (1 << 9) | (1 << 15); // GUILDS | GUILD_MESSAGES | MESSAGE_CONTENT

const OP_DISPATCH = 0;
const OP_HEARTBEAT = 1;
const OP_IDENTIFY = 2;
const OP_PRESENCE_UPDATE = 3;
const OP_RESUME = 6;
const OP_RECONNECT = 7;
const OP_INVALID_SESSION = 9;
const OP_HELLO = 10;
const OP_HEARTBEAT_ACK = 11;

export interface GatewayMessageEvent {
  channel_id: string;
  content: string;
  webhook_id?: string;
  author?: { id: string; username: string; global_name?: string | null; bot?: boolean };
  member?: { nick?: string | null };
}

/** One entry of the bot's presence; type is a Discord activity type (0 playing ... 5 competing). */
export interface GatewayActivity {
  name: string;
  type: number;
  /** Shown as the status text for custom activities (type 4), ignored otherwise. */
  state?: string;
}

export class DiscordGateway {
  private ws: WebSocket | null = null;
  private running = false;
  private seq: number | null = null;
  private sessionId: string | null = null;
  private resumeUrl: string | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private ackReceived = true;
  private backoff = 1000;
  private presence: GatewayActivity | null = null;

  constructor(
    private readonly token: string,
    private readonly onMessage: (m: GatewayMessageEvent) => void,
    private readonly log: (...args: unknown[]) => void,
  ) {}

  start(): void {
    this.running = true;
    this.connect();
  }

  /**
   * Sets the bot's status (op 3). Kept as the latest known presence and replayed on
   * identify/resume, so a reconnect doesn't leave a stale status behind. Discord allows
   * 5 presence updates per 20s per session - callers must throttle.
   */
  setPresence(activity: GatewayActivity): void {
    this.presence = activity;
    this.send(OP_PRESENCE_UPDATE, presencePayload(activity));
  }

  stop(): void {
    this.running = false;
    this.clearHeartbeat();
    this.ws?.close(1000);
    this.ws = null;
  }

  private connect(): void {
    const url = this.sessionId && this.resumeUrl
      ? `${this.resumeUrl}/?v=10&encoding=json`
      : GATEWAY_URL;
    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onmessage = (event) => this.handlePacket(JSON.parse(String(event.data)));

    ws.onclose = (event) => {
      if (ws !== this.ws) return; // superseded by a newer connection
      this.clearHeartbeat();
      if (!this.running) return;
      this.log(`gateway closed (${event.code}), reconnecting in ${this.backoff}ms`);
      // 4004 = auth failed: retrying with the same bad token forever is pointless spam,
      // but we keep a slow retry so a corrected token (via restart) isn't required mid-run.
      setTimeout(() => this.running && this.connect(), event.code === 4004 ? 60_000 : this.backoff);
      this.backoff = Math.min(this.backoff * 2, 30_000);
    };

    ws.onerror = () => {
      // onclose fires afterwards and handles the retry
    };
  }

  private handlePacket(packet: { op: number; d: any; s?: number | null; t?: string | null }): void {
    if (packet.s != null) this.seq = packet.s;
    switch (packet.op) {
      case OP_HELLO: {
        this.startHeartbeat(packet.d.heartbeat_interval);
        if (this.sessionId) {
          this.send(OP_RESUME, { token: this.token, session_id: this.sessionId, seq: this.seq });
        } else {
          this.identify();
        }
        break;
      }
      case OP_HEARTBEAT:
        this.send(OP_HEARTBEAT, this.seq);
        break;
      case OP_HEARTBEAT_ACK:
        this.ackReceived = true;
        break;
      case OP_RECONNECT:
        this.log("gateway asked us to reconnect (resumable)");
        this.ws?.close(4900);
        break;
      case OP_INVALID_SESSION:
        this.log(`gateway session invalid (resumable: ${packet.d === true})`);
        if (packet.d !== true) {
          this.sessionId = null;
          this.resumeUrl = null;
        }
        this.ws?.close(4901);
        break;
      case OP_DISPATCH:
        this.handleDispatch(packet.t ?? "", packet.d);
        break;
    }
  }

  private handleDispatch(type: string, d: any): void {
    switch (type) {
      case "READY":
        this.sessionId = d.session_id;
        this.resumeUrl = d.resume_gateway_url ?? null;
        this.backoff = 1000;
        this.log(`gateway ready as ${d.user?.username ?? "?"} (session ${d.session_id})`);
        break;
      case "RESUMED":
        this.backoff = 1000;
        this.log("gateway session resumed");
        // A resumed session keeps whatever presence it had; ours may have moved on.
        if (this.presence) this.send(OP_PRESENCE_UPDATE, presencePayload(this.presence));
        break;
      case "MESSAGE_CREATE":
        this.onMessage(d as GatewayMessageEvent);
        break;
    }
  }

  private identify(): void {
    this.send(OP_IDENTIFY, {
      token: this.token,
      intents: INTENTS,
      properties: { os: "linux", browser: "multichat-bridge", device: "multichat-bridge" },
      // Carried on identify so a fresh session comes up with the status already set.
      presence: this.presence ? presencePayload(this.presence) : undefined,
    });
  }

  private startHeartbeat(intervalMs: number): void {
    this.clearHeartbeat();
    this.ackReceived = true;
    const beat = () => {
      if (!this.ackReceived) {
        // Zombie connection: no ACK since our last heartbeat. Close and resume.
        this.log("gateway heartbeat not acknowledged - reconnecting");
        this.ws?.close(4902);
        return;
      }
      this.ackReceived = false;
      this.send(OP_HEARTBEAT, this.seq);
    };
    // Discord asks for jitter on the first beat to spread load.
    setTimeout(() => {
      beat();
      this.heartbeatTimer = setInterval(beat, intervalMs);
    }, Math.floor(intervalMs * Math.random()));
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private send(op: number, d: unknown): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ op, d }));
    }
  }
}

/** Presence update / identify presence body. */
function presencePayload(activity: GatewayActivity) {
  return { since: null, activities: [activity], status: "online", afk: false };
}
