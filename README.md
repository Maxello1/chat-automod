# Chat AutoMod

Chat AutoMod is a server-side Fabric moderation mod for Minecraft. It evaluates the original signed body of public player chat, applies deterministic rules, and either allows the original message or blocks it. Messages are never reformatted or rebroadcast, and vanilla clients can join without installing the mod.

The initial release targets Minecraft 26.2. A loadable Minecraft 1.21.1 Fabric adapter scaffold is included in the same repository and shares the complete Java 21 moderation library. Its public-chat bridge is connected, while its administrative configuration and persistence surface is not yet feature-complete.

## Requirements

| Target | Java | Fabric Loader | Fabric API |
| --- | ---: | ---: | ---: |
| Minecraft 26.2 | 25 | 0.19.3 or newer | 0.154.2+26.2 or newer |
| Minecraft 1.21.1 | 21 | 0.19.3 or newer | 0.116.14+1.21.1 or newer |

For a Minecraft 26.2 server, copy its versioned jar into the server's `mods` directory alongside Fabric API. Clients do not need Chat AutoMod. The 1.21.1 jar is currently intended for port validation rather than production deployment.

## Building

Install both JDK 21 and JDK 25, then run Gradle itself with JDK 25 for the Minecraft 26.2 build. Gradle selects JDK 21 for the shared core and the 1.21.1 adapter through toolchains.

```text
./gradlew :core:test
./gradlew :fabric-26.2:build
./gradlew :fabric-1.21.1:build
```

The versioned jars are written under each adapter's `build/libs` directory. Each jar nests the shared core library.

## Configuration

The active configuration is `config/chatautomod/automod.json`. A complete default is created on first startup. Runtime JSON is strict: invalid values are reported with their JSON paths, and `/automod reload` keeps the previous configuration active unless the entire replacement validates and compiles successfully. A fully annotated reference is available in [`docs/automod.example.jsonc`](docs/automod.example.jsonc).

The moderation engine includes these detector families:

| Rule | What it checks |
| --- | --- |
| Message length | Messages exceeding the configured Unicode code-point limit |
| Caps | Uppercase ratio across letters only |
| Repeated characters | Excessive Unicode letter or symbol runs |
| Exact duplicate | Recent canonical messages from the same player |
| Rapid spam | Minimum interval and a bounded sliding window |
| Similarity spam | Bounded token and edit-distance comparisons against that player's recent messages |
| Filters | Stable word, phrase, substring, and compact-spelling rules with exceptions |
| Advertising | Domains, obfuscated domains, valid IPv4 addresses and ports, and Discord invites |

Normalization is performed once per message. It applies NFKC, removes unsafe formatting characters, normalizes Unicode whitespace, applies configured conservative lookalike and leetspeak substitutions, normalizes common link separators, and exposes canonical, deobfuscated, compact, and repeated-character forms to every detector.

Allowed advertising domains use host boundaries. Allowing `example.org` also allows `www.example.org` and `play.example.org`, but does not allow `fakeexample.org` or `example.org.evil.com`.

Filtered-content rules support `WORD`, `PHRASE`, `SUBSTRING`, and `COMPACT` matching. Exceptions are checked before a rule is returned. Compact matching rejects short terms by default to reduce false positives. Administrator-supplied regular expressions are not accepted.

## Scoring and actions

Each violation creates its own expiring score entry. Expired entries are pruned when the player is evaluated, so point decay is deterministic and requires no global timer. A rule ID contributes points at most once per message, and the configured per-message cap is applied after matches are deduplicated.

Thresholds default to `HIGHEST_CROSSED`. If one message crosses several thresholds, only the highest crossed threshold runs. A threshold does not repeat while the score remains above it; it can run again after decay takes the score below it and a later violation crosses it again.

Rules and thresholds can request:

- Staff notification
- Player warning
- Temporary mute
- Kick
- A validated server command using `{player}`, `{uuid}`, `{points}`, and `{rule}`

Blocking is a message decision, not an action. Message replacement and modified rebroadcasting are intentionally not supported.

Temporary mutes block chat before normal detection and do not add points. Mute notices have a cooldown, and manual and automatic mutes are stored across restarts.

## Commands

| Command | Purpose |
| --- | --- |
| `/automod reload` | Atomically validate and activate the configuration |
| `/automod test <message>` | Run the active pipeline without changing state or executing actions |
| `/automod history <player> [page]` | Show retained moderation history |
| `/automod violations <player> [page]` | Show retained violations and scores |
| `/automod clear <player>` | Clear a player's moderation state |
| `/automod mute <player> <duration> [reason]` | Apply a temporary mute |
| `/automod unmute <player>` | Remove a temporary mute |
| `/automod inspect [on|off]` | Toggle detailed alerts for the executing staff member |

Durations accept `s`, `m`, `h`, `d`, and `w`, such as `30s`, `5m`, or `1d`. Zero, negative, overflowing, and over-limit durations are rejected.

`/automod test` reports normalized forms, every matching rule ID, the allow/block decision, points that would be added, and the threshold action that would result. It uses a detached player-state snapshot and cannot change histories, scores, mutes, notification cooldowns, logs, or staff preferences.

## Permissions

| Node | Purpose |
| --- | --- |
| `chatautomod.admin` | Full command access |
| `chatautomod.reload` | Reload configuration |
| `chatautomod.test` | Dry-run messages |
| `chatautomod.history` | View history and violations |
| `chatautomod.clear` | Clear moderation state |
| `chatautomod.mute` | Mute and unmute players |
| `chatautomod.inspect` | Toggle detailed alerts |
| `chatautomod.alerts` | Receive staff alerts |
| `chatautomod.bypass` | Bypass all moderation |
| `chatautomod.bypass.spam` | Bypass spam rules |
| `chatautomod.bypass.filter` | Bypass content filters |
| `chatautomod.bypass.advertising` | Bypass advertising rules |

Compatible installed permission providers are used when available. Otherwise, the configured Minecraft operator level is used.

## Storage and privacy

Mutable data is kept under `<world>/chatautomod/`:

```text
state.json
state.json.bak
logs/automod-YYYY-MM-DD.jsonl
```

Active mutes, unexpired score entries, retained violation history, and last known player names are persisted. Short-term spam and similarity buffers are not. State snapshots, including retained history, are queued away from the chat path, written to temporary files, and atomically replace the previous files where the filesystem supports it.

Logs use bounded JSON Lines output and configured retention. Original chat text is omitted from persistent state and logs unless its storage option is explicitly enabled. Control characters and line breaks are sanitized before logging.

## Project structure

- `core` contains the Java 21 configuration, normalization, detection, score, state, and persistence logic. It has no Minecraft or loader imports.
- `fabric-26.2` contains the Minecraft 26.2 event, command, permission, component, lifecycle, action, and file-system adapter.
- `fabric-1.21.1` contains the official-Mojang-mapped Minecraft 1.21.1 adapter scaffold and depends on the same core jar.

Private-message command interception, message replacement, webhooks, a client interface, arbitrary regular expressions, IPv6 advertising checks, and other loaders are outside this release.

See [MANUAL_TESTING.md](MANUAL_TESTING.md) for the dedicated-server smoke checklist.
