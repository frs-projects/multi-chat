// Live counters behind the bot's Discord status: players online and servers up.
//
// Two sources of truth, in this order:
//   roster  periodic "here is exactly who is online" broadcast from the Link-Mod
//           (forward.rosterSeconds). Authoritative, self-healing, and doubles as a
//           heartbeat - a server whose rosters stop arriving is dropped (see expire()).
//   join/leave/status  the fallback tally, used for servers that don't send rosters
//           (feature disabled, older mod version) and to keep the number responsive
//           between two rosters.
//
// Players are tracked as a set per source rather than a bare +1/-1 counter: a duplicate
// join (reconnect storm, replayed stream entry) then can't inflate the number, and a
// server going down drops exactly its own players. Tally-only servers start at zero on
// bridge start, so they're counted from their next event onward; roster servers are
// correct one interval after the bridge comes up.

import type { ChatMessage } from "../../core/types.ts";

/** status contents meaning "this server is up"; anything else (stopping, ...) means down. */
const ONLINE_STATUS = new Set(["started", "start", "online", "up"]);

export interface PresenceCounts {
  players: number;
  servers: number;
}

interface SourceState {
  /** player keys currently online, from rosters and join/leave events alike */
  players: Set<string>;
  /** exact count from the last roster; null for sources that never sent one */
  rosterCount: number | null;
  /** how many names that roster carried - fewer than rosterCount when the list was capped */
  rosterNames: number;
  /** epoch millis of the last roster; 0 = never */
  rosterAt: number;
}

export class PresenceTracker {
  private readonly sources = new Map<string, SourceState>();

  /** Folds one stream event in; true when the counts changed and the status needs a push. */
  apply(msg: ChatMessage, now: number = Date.now()): boolean {
    return this.changed(() => this.mutate(msg, now));
  }

  /**
   * Drops servers whose rosters stopped arriving (crash, network partition, no "stopping"
   * event). Only servers that ever sent a roster are subject to this - the others have no
   * heartbeat to miss.
   */
  expire(ttlMs: number, now: number = Date.now()): boolean {
    return this.changed(() => {
      for (const [source, state] of this.sources) {
        if (state.rosterAt > 0 && now - state.rosterAt > ttlMs) this.sources.delete(source);
      }
    });
  }

  counts(): PresenceCounts {
    let players = 0;
    for (const state of this.sources.values()) players += playerCount(state);
    return { players, servers: this.sources.size };
  }

  /** Fills {players} {servers} in the status template. */
  render(template: string): string {
    const { players, servers } = this.counts();
    return template
      .replaceAll("{players}", String(players))
      .replaceAll("{servers}", String(servers));
  }

  private mutate(msg: ChatMessage, now: number): void {
    if (!msg.source) return;
    switch (msg.type) {
      case "roster":
        this.applyRoster(msg, now);
        break;
      case "join": {
        const key = playerKey(msg);
        // A join proves the server is up even if we missed (or never got) its status event.
        if (key) this.state(msg.source).players.add(key);
        break;
      }
      case "leave": {
        const key = playerKey(msg);
        if (key) this.sources.get(msg.source)?.players.delete(key);
        break;
      }
      case "status":
        // Down: the server and its players leave the tally. Up: it is freshly started, so
        // whatever we had for it is stale - reset it to online with nobody on.
        this.sources.delete(msg.source);
        if (ONLINE_STATUS.has(msg.content.trim().toLowerCase())) this.state(msg.source);
        break;
    }
  }

  private applyRoster(msg: ChatMessage, now: number): void {
    const names = msg.content.split(",").map((n) => n.trim()).filter(Boolean);
    const reported = Number.parseInt(msg.meta, 10);
    const state = this.state(msg.source);
    state.players = new Set(names);
    state.rosterNames = names.length;
    state.rosterCount = Number.isFinite(reported) && reported >= 0 ? reported : names.length;
    state.rosterAt = now;
  }

  private state(source: string): SourceState {
    let state = this.sources.get(source);
    if (!state) {
      state = { players: new Set(), rosterCount: null, rosterNames: 0, rosterAt: 0 };
      this.sources.set(source, state);
    }
    return state;
  }

  /** Runs a mutation and reports whether it moved either counter. */
  private changed(mutate: () => void): boolean {
    const before = this.counts();
    mutate();
    const after = this.counts();
    return before.players !== after.players || before.servers !== after.servers;
  }
}

/**
 * Roster count corrected by the joins/leaves seen since, so the number stays live between
 * two rosters. Equals the set size while rosters carry every name (the normal case); the
 * arithmetic only matters for a capped list, where the set is short by a fixed offset.
 */
function playerCount(state: SourceState): number {
  if (state.rosterCount === null) return state.players.size;
  return Math.max(0, state.rosterCount + state.players.size - state.rosterNames);
}

/** Name first: it is what rosters carry, so join/leave events line up with them. */
function playerKey(msg: ChatMessage): string {
  return msg.name || msg.uuid;
}
