# Multi-Chat

Links multiple Minecraft servers' chats together through a Redis stream, and optionally
bridges them to external platforms (Discord first).

- [x] Link multiple servers chats together through a redis instance
- [x] Optionally send those messages to discord (or other platforms) and back

```
MC server A (Link-Mod) ─┐                      ┌─ MC server B (Link-Mod)
                        ├── Redis stream ──────┤
Discord  (bridge) ──────┘   multichat:events   └─ CLI / future connectors (bridge)
```

Every endpoint writes flat field/value entries (`source`, `type`, `uuid`, `name`,
`content`, `ts`, `meta`) onto one Redis **stream** and consumes it through its own
consumer group — so endpoints that were down catch up on missed messages (capped by
`maxCatchupAgeSeconds` to avoid replay spam). Each consumer skips entries whose
`source` is its own id; that is the entire echo-loop guard, which is why **every
`server.id` in the network must be unique**.

## Repo layout

| Path | What |
|---|---|
| `mod/core/` | Loader-independent Java core: hand-rolled RESP client, stream bus, message model, public API. Zero dependencies — no shading, trivially reused by future Fabric/NeoForge variants. |
| `mod/src/` | Thin Forge adapter: config, event taps, chat printing, `/multichat` commands. One Stonecutter source tree; loader-specific files are gated with `//? if forge {`. |
| `mod/versions/<node>/` | Per-node dependency versions (`1.20.1-forge` today). Adding a node is one `match(...)` line in `mod/settings.gradle.kts` plus this file. |
| `bridge/` | Bun/TypeScript bridge framework with pluggable connectors (`discord`, `cli`). Zero npm dependencies. |

## Link-Mod (Forge 1.20.1)

Build: `cd mod && ./gradlew buildAll` → `mod/versions/1.20.1-forge/build/libs/multichat-<version>+1.20.1-forge.jar`
(`./gradlew collectJars` copies every node's jar into `mod/build/libs`). Same build framework as
ModSync: Stonecutter + Architectury Loom, Gradle 9.7 with its daemon on **Java 25**
(`mod/gradle/gradle-daemon-jvm.properties`); the jar itself targets Java 17. `./gradlew checkAll`
also runs `verifyModMetadata`, which fails the build if a jar is missing its `mods.toml` or
`pack.mcmeta`.

Server-only — clients never need it installed. On first boot it writes
`config/multichat-common.toml`; set `server.id` (the mod stays dormant while it is
empty) and the `[redis]` connection, then restart or run `/multichat reload`.

Forwarded out of the box (each toggleable under `[forward]`): chat, join/leave, death
messages, advancements (respecting `announceAdvancements` / `showDeathMessages`
gamerules), server started/stopping status, and a periodic `roster` event listing the
online players (`forward.rosterSeconds`, default 30, `0` disables). Rosters are
telemetry — never printed in chat — and let consumers show an exact player count instead
of tallying join/leave events. What gets *printed* locally is
controlled under `[display]`: per-type format templates with `{source}` `{name}`
`{message}` placeholders and § color codes, plus source/type ignore lists.

Death/advancement texts are rendered server-side in English — a relay limitation worth
knowing about, not a bug.

### Securing the Redis connection

Any Redis 6+ server works; what runs on it besides the stream is up to you. For a Redis
reachable over the internet, enable TLS and give each endpoint its own ACL user:

```toml
[redis]
host = "redis.example.org"
port = 6380
tls = true
username = "multichat-EU_1"   # optional ACL user; leave out for password-only AUTH
password = "…"
```

With `tls = true` the certificate and host name are verified, so the certificate must be
trusted by the server's JVM (a public CA works as is). The mod and the bridge only need the
stream key and a handful of commands, so a minimal ACL user looks like:

```
ACL SETUSER multichat-EU_1 on >password resetkeys ~multichat:events -@all +xadd +xreadgroup +xack +xgroup|create +ping
```

The bridge connects with `REDIS_URL=rediss://<user>:<password>@<host>:<port>`. Anything else
that should see the network's chat (a web dashboard, moderation tooling, statistics) can
read the same stream through its own consumer group.

`/multichat status` (op) shows connection state; `/multichat reload` applies config
changes live. Redis being down never blocks the server: publishing is queue-based and
both connections reconnect with backoff.

### API for other mods

Add `mod/core` sources/jar to your compile classpath (the classes ship inside the
multichat jar at runtime):

```java
if (ModList.get().isLoaded("multichat")) {
    MultiChatApi.publish("bounty", player.getUUID(), player.getName().getString(),
                         "claimed the bounty on Steve");
}
```

Custom types ride the same stream; other servers print them with the `default` format
(or their own template if one is configured under `[display.formats]`), and the bridge
routes them via each channel's `types` filter. The `source` field is always stamped by
the mod — callers cannot spoof another server.

## Bridge (Bun)

```sh
cd bridge
cp .env.example .env   # then edit
bun run bridge.ts
```

Or containerized: `cd bridge && docker compose up -d --build`. Configuration is
**env-only** — no config files. See [bridge/.env.example](bridge/.env.example) for the
full reference; in a deploy platform (Dokploy etc.) just set the variables in the UI.

Zero npm dependencies; Redis via Bun's built-in client, Discord via a minimal
hand-rolled Gateway client. Idle cost is one blocked Redis read plus a heartbeat.
Each connector gets its own consumer group (`bridge:<id>`) and loop, so a slow platform
never blocks another.

### Discord setup

1. Create an application + bot at <https://discord.com/developers/applications>, copy the token.
2. **Enable the "Message Content Intent"** under Bot → Privileged Gateway Intents —
   without it Discord delivers empty message content and inbound bridging silently does nothing.
3. Invite the bot to your guild (scope `bot`, permission View Channels + Send Messages).
4. Create a webhook in each bridged channel (Channel settings → Integrations) for
   outbound messages with per-player name + avatar; without a webhook the bridge falls
   back to plain bot messages.
5. Set one `DISCORD_CHANNEL_<n>_*` variable group per channel (numbering starts at 1):
   `_ID` for inbound reading, `_WEBHOOK` for outbound, `_TYPES`/`_SOURCES` filters,
   `_INBOUND` toggle. The connector activates as soon as one group is set.

Mentions are neutralized (`@everyone` etc. never ping) and Minecraft § codes are stripped.

### Bot status counters

With a bot token the connector shows live counters as its Discord status, e.g.
*Watching 12 players on 3 servers*, taken from the stream itself:

- **`roster` events** are authoritative: each one replaces what the bridge knew about that
  server, so a lost event or a crash can't leave the count drifting. They also act as a
  heartbeat — a server that stops sending them for `DISCORD_PRESENCE_ROSTER_TTL_SECONDS`
  (default 120) drops out of the counters even without a `stopping` event.
- **`join`/`leave`/`status` events** keep the number live between two rosters, and are the
  whole story for servers that don't send rosters (`rosterSeconds = 0`, older mod build).
  Players are tracked as a set per server, so duplicate joins can't inflate the count;
  `started` resets a server to empty, `stopping` removes it and its players.

A roster-less server therefore starts at zero when the bridge boots and is counted from
its next event onward; a roster-sending one is exact within one interval. Rosters are not
relayed to any channel unless a rule names the type (`DISCORD_CHANNEL_n_TYPES=roster`) —
a `*` filter deliberately doesn't match them. Status updates are throttled to one per 15s
(Discord allows 5 per 20s) and re-pushed after a reconnect. Configure with
`DISCORD_PRESENCE_TEMPLATE` (`{players}`, `{servers}`), `DISCORD_PRESENCE_ACTIVITY`,
`DISCORD_PRESENCE_ENABLED`.

### Adding another platform

One folder under `bridge/src/connectors/<name>/` exporting a factory that returns the
`Connector` interface (`start`/`stop`/`deliver` + `ctx.publish`), a few env variables in
`config.ts`, one line in `bridge.ts`. The `cli` connector (~40 lines) is the reference.

## Local testing without Discord

```sh
docker compose up -d                 # throwaway Redis on 127.0.0.1:6379
cd mod && ./gradlew :1.20.1-forge:runServer   # dev server; set server.id in versions/1.20.1-forge/run/server/config/
# in a second terminal:
cd bridge && CLI_ENABLED=true bun run bridge.ts
```

Typed lines in the bridge terminal appear in the server chat and vice versa. A second
server can be simulated with redis-cli:

```sh
redis-cli XADD multichat:events '*' source EU_2 type chat name Tester content hello ts $(date +%s%3N)
redis-cli XRANGE multichat:events - +        # inspect what the mod/bridge wrote
```

Unit tests: `cd mod && ./gradlew :core:test` and `cd bridge && bun test`.
`bridge/tools/spike-streams.ts` documents/verifies the Bun Redis stream behavior the
bus relies on.

## License

LGPL-3.0-only. See [COPYING.LESSER](COPYING.LESSER) and [COPYING](COPYING).
