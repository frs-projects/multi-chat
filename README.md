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

The bridges (Discord, CLI, …) live in their own repository: [multichat-bridges](https://github.com/frs-projects/multichat-bridges).

Every endpoint writes flat field/value entries (`source`, `type`, `uuid`, `name`,
`content`, `ts`, `meta`) onto one Redis **stream** and consumes it through its own
consumer group — so endpoints that were down catch up on missed messages (capped by
`maxCatchupAgeSeconds` to avoid replay spam). Each consumer skips entries whose
`source` is its own id; that is the entire echo-loop guard, which is why **every
`server.id` in the network must be unique**.

## Repo layout

| Path | What |
|---|---|
| `mod/core/` | Loader-independent Java core: hand-rolled RESP client, stream bus, message model, public API. Zero dependencies — no shading, shared by every loader node. |
| `mod/src/` | One Stonecutter source tree. `mc/` holds the shared runtime (config, event taps, chat printing, `/multichat` commands); `forge/` and `neoforge/` are thin entry points gated with `//? if forge {` / `//? if neoforge {` that forward loader events to it. Git holds the tree as the `1.21.1-neoforge` node, so the Forge file is committed commented out. |
| `mod/versions/<node>/` | Per-node dependency versions (`1.20.1-forge`, `1.21.1-neoforge`). Adding a node is one `match(...)` line in `mod/settings.gradle.kts` plus this file. |

## Link-Mod (Forge 1.20.1, NeoForge 1.21.1)

Build: `cd mod && ./gradlew buildAll` → `mod/versions/<node>/build/libs/multichat-<version>+<node>.jar` (`./gradlew collectJars` gathers them into `mod/build/libs`)
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

## Bridges

The bridge to Discord and other platforms is a separate Bun service: see
[multichat-bridges](https://github.com/frs-projects/multichat-bridges) for setup, Discord configuration and adding connectors.

## Local testing without Discord

```sh
docker compose up -d                 # throwaway Redis on 127.0.0.1:6379
cd mod && ./gradlew :1.20.1-forge:runServer   # dev server; set server.id in versions/1.20.1-forge/run/server/config/
# in a second terminal, from a multichat-bridges checkout:
CLI_ENABLED=true bun run bridge.ts
```

Typed lines in the bridge terminal appear in the server chat and vice versa. A second
server can be simulated with redis-cli:

```sh
redis-cli XADD multichat:events '*' source EU_2 type chat name Tester content hello ts $(date +%s%3N)
redis-cli XRANGE multichat:events - +        # inspect what the mod/bridge wrote
```

Unit tests: `cd mod && ./gradlew :core:test`.

## License

LGPL-3.0-only. See [COPYING.LESSER](COPYING.LESSER) and [COPYING](COPYING).
