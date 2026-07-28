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
| `mod/forge-1.20.1/` | Thin Forge adapter: config, event taps, chat printing, `/multichat` commands. |
| `bridge/` | Bun/TypeScript bridge framework with pluggable connectors (`discord`, `cli`). Zero npm dependencies. |

## Link-Mod (Forge 1.20.1)

Build (needs JDK 17 for Gradle): `cd mod && ./gradlew build` → `mod/forge-1.20.1/build/libs/multichat-1.0.0.jar`

Server-only — clients never need it installed. On first boot it writes
`config/multichat-common.toml`; set `server.id` (the mod stays dormant while it is
empty) and the `[redis]` connection, then restart or run `/multichat reload`.

Forwarded out of the box (each toggleable under `[forward]`): chat, join/leave, death
messages, advancements (respecting `announceAdvancements` / `showDeathMessages`
gamerules), and server started/stopping status. What gets *printed* locally is
controlled under `[display]`: per-type format templates with `{source}` `{name}`
`{message}` placeholders and § color codes, plus source/type ignore lists.

Death/advancement texts are rendered server-side in English — a relay limitation worth
knowing about, not a bug.

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

### Adding another platform

One folder under `bridge/src/connectors/<name>/` exporting a factory that returns the
`Connector` interface (`start`/`stop`/`deliver` + `ctx.publish`), a few env variables in
`config.ts`, one line in `bridge.ts`. The `cli` connector (~40 lines) is the reference.

## Local testing without Discord

```sh
docker compose up -d                 # throwaway Redis on 127.0.0.1:6379
cd mod && ./gradlew :forge-1.20.1:runServer   # dev server; set server.id in run/config/
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
