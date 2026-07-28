// Live counters behind the bot's Discord status: players online and servers up, derived
// purely from the join/leave/status events flowing past on the stream. Nobody publishes
// totals, so the bridge keeps its own tally.
//
// Players are tracked as a set per source instead of a bare +1/-1 counter: a duplicate
// join (reconnect storm, replayed stream entry) then can't inflate the number, and a
// server going down drops exactly its own players. The tally starts empty on bridge
// start - servers that were already up are only counted once they emit an event.

import type { ChatMessage } from "../../core/types.ts";

/** status contents meaning "this server is up"; anything else (stopping, ...) means down. */
const ONLINE_STATUS = new Set(["started", "start", "online", "up"]);

export interface PresenceCounts {
  players: number;
  servers: number;
}

export class PresenceTracker {
  private readonly playersBySource = new Map<string, Set<string>>();
  private readonly onlineServers = new Set<string>();

  /** Folds one stream event in; true when the counts changed and the status needs a push. */
  apply(msg: ChatMessage): boolean {
    switch (msg.type) {
      case "join":
        return this.addPlayer(msg.source, playerKey(msg));
      case "leave":
        return this.removePlayer(msg.source, playerKey(msg));
      case "status":
        return ONLINE_STATUS.has(msg.content.trim().toLowerCase())
          ? this.serverUp(msg.source)
          : this.serverDown(msg.source);
      default:
        return false;
    }
  }

  counts(): PresenceCounts {
    let players = 0;
    for (const set of this.playersBySource.values()) players += set.size;
    return { players, servers: this.onlineServers.size };
  }

  /** Fills {players} {servers} in the status template. */
  render(template: string): string {
    const { players, servers } = this.counts();
    return template
      .replaceAll("{players}", String(players))
      .replaceAll("{servers}", String(servers));
  }

  private addPlayer(source: string, key: string): boolean {
    if (!source || !key) return false;
    // A join proves the server is up even if we missed (or never got) its status event.
    const wasOffline = !this.onlineServers.has(source);
    this.onlineServers.add(source);
    let players = this.playersBySource.get(source);
    if (!players) this.playersBySource.set(source, (players = new Set()));
    const isNew = !players.has(key);
    players.add(key);
    return isNew || wasOffline;
  }

  private removePlayer(source: string, key: string): boolean {
    if (!source || !key) return false;
    return this.playersBySource.get(source)?.delete(key) ?? false;
  }

  /** A server announcing "started" is freshly up, so its player list resets to empty. */
  private serverUp(source: string): boolean {
    if (!source) return false;
    const hadPlayers = (this.playersBySource.get(source)?.size ?? 0) > 0;
    this.playersBySource.delete(source);
    const wasOffline = !this.onlineServers.has(source);
    this.onlineServers.add(source);
    return hadPlayers || wasOffline;
  }

  private serverDown(source: string): boolean {
    if (!source) return false;
    const hadPlayers = (this.playersBySource.get(source)?.size ?? 0) > 0;
    this.playersBySource.delete(source);
    return this.onlineServers.delete(source) || hadPlayers;
  }
}

/** uuid where we have one, display name otherwise (bridge-published events carry no uuid). */
function playerKey(msg: ChatMessage): string {
  return msg.uuid || msg.name;
}
