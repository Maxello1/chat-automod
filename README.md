# Chat AutoMod

Chat AutoMod is a production-ready, server-side Fabric moderation mod for Minecraft 1.21.1. It evaluates the original signed body of public player chat, applies deterministic rules, and either allows the original message or blocks it. Messages are never reformatted or rebroadcast, and vanilla clients can join without installing the mod.

The Java 21 `core` module contains the platform-independent moderation engine. The `fabric-1.21.1` adapter owns the Minecraft and Fabric integration, including lifecycle handling, commands, permissions, persistence, audit logging, and signed public-chat interception.

## Requirements

| Target | Java | Fabric Loader | Fabric API |
| --- | ---: | ---: | ---: |
| Minecraft 1.21.1 | 21 | 0.19.3 or newer | 0.116.14+1.21.1 or newer |

Copy the 1.21.1 jar into the server's `mods` directory alongside Fabric API. Clients do not need Chat AutoMod.

## Building

Install JDK 21, then run the focused verification commands:

```text
./gradlew :core:test
./gradlew :fabric-1.21.1:build
./gradlew releaseBuild
```

The distributable jar is written to `fabric-1.21.1/build/libs/chat-automod-fabric-1.21.1-<version>.jar`. It embeds the core library; a separate core jar is not required on the server.

## Configuration

The active configuration is `config/chatautomod/automod.json`. The mod creates a complete default configuration and editable filter packs under `config/chatautomod/filters/` on first startup.

Runtime JSON is strict. `/automod reload` reads the main file, every active filter pack, and the targeted exceptions file as one replacement. Errors include their source file and JSON path; no part of the replacement becomes active unless the complete set validates and compiles successfully. A commented main-configuration reference is available in [`docs/automod.example.jsonc`](docs/automod.example.jsonc).

The moderation engine includes these detector families:

| Rule | What it checks |
| --- | --- |
| Message length | Messages exceeding the configured Unicode code-point limit |
| Caps | Uppercase ratio across letters only |
| Repeated characters | Excessive Unicode letter or symbol runs |
| Exact duplicate | Recent canonical messages from the same player |
| Rapid spam | Minimum interval and a bounded sliding window |
| Similarity spam | Bounded token and edit-distance comparisons against that player's recent messages |
| Filter packs | Normalized word, phrase, compact, and controlled built-in-pattern rules with targeted exceptions |
| Advertising | Domains, obfuscated domains, valid IPv4 addresses and ports, and Discord invites |

Normalization is performed once per message. It applies NFKC, removes unsafe formatting characters, normalizes Unicode whitespace, applies configured conservative lookalike and leetspeak substitutions, normalizes common link separators, and exposes canonical, deobfuscated, compact, and repeated-character forms. Filter rules additionally use their own conservative text form so content-specific substitutions do not weaken advertising or ordinary-message normalization.

Allowed advertising domains use host boundaries. Allowing `example.org` also allows `www.example.org` and `play.example.org`, but does not allow `fakeexample.org` or `example.org.evil.com`.

Filter packs support `NORMALIZED_WORD`, `NORMALIZED_PHRASE`, `COMPACT_WORD`, `COMPACT_PHRASE`, and controlled `BUILT_IN_PATTERN` matching. Exceptions are normalized with their target rule and checked before a match is returned. Administrator-supplied Java regular expressions are not accepted.

## Scoring and actions

Each violation creates its own expiring score entry. Expired entries are pruned when the player is evaluated, so point decay is deterministic and requires no global timer. A rule ID contributes points at most once per message, and the configured per-message cap is applied after matches are deduplicated.

Thresholds default to `HIGHEST_CROSSED`. If one message crosses several thresholds, only the highest crossed threshold runs. A threshold does not repeat while the score remains above it; it can run again after decay takes the score below it and a later violation crosses it again.

Rules and thresholds can request:

- Staff notification
- Player warning
- Temporary mute
- Permanent mute for explicitly configured severe rules
- Kick
- A validated server command using `{player}`, `{uuid}`, `{points}`, and `{rule}`

Blocking is a message decision, not an action. Message replacement and modified rebroadcasting are intentionally not supported.

Temporary and permanent mutes block chat before normal detection and do not add points. Mute notices have a cooldown, and manual and automatic mutes are stored across restarts. A mute is removed only by expiry or `/automod unmute`, never by clearing score or history.

## Commands

| Command | Purpose |
| --- | --- |
| `/automod` | Show runtime, configuration, rule, and tracked-player status |
| `/automod reload` | Atomically validate and activate the configuration |
| `/automod test <message>` | Run the active pipeline without changing state or executing actions |
| `/automod history <player> [page]` | Show retained moderation history |
| `/automod violations <player> [page]` | Show active score entries, threshold state, mute state, and retained-history count |
| `/automod clear <player> <score\|history\|spam\|all>` | Clear only the selected non-mute state |
| `/automod mute <player> <duration\|permanent> [reason]` | Apply a temporary or permanent mute |
| `/automod unmute <player>` | Remove either kind of mute |
| `/automod inspect [on|off]` | Toggle detailed alerts for the executing staff member |
| `/automod permissions <player>` | Explain command, alert, and live-chat bypass decisions |

Durations accept `s`, `m`, `h`, `d`, and `w`, such as `30s`, `5m`, or `1d`. Zero, negative, overflowing, and over-limit durations are rejected.

`/automod test` reports normalized forms, matches and prevented matches, the allow/block decision, points that would be added, the predicted action, and whether the executor would bypass live moderation. The test itself always evaluates the message. It uses a detached player-state snapshot and cannot change histories, scores, spam buffers, mutes, notification cooldowns, logs, snapshots, or staff preferences.

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
| `chatautomod.permissions` | Inspect effective moderation permissions |
| `chatautomod.alerts` | Receive staff alerts |
| `chatautomod.bypass` | Bypass all moderation |
| `chatautomod.bypass.spam` | Bypass spam rules |
| `chatautomod.bypass.filter` | Bypass content filters |
| `chatautomod.bypass.advertising` | Bypass advertising rules |
| `chatautomod.bypass.security` | Bypass security-category rules |

Compatible installed permission providers are used when available. Command and staff permissions can fall back to separately configured Minecraft operator levels.

Moderation bypass is deliberately separate. With the default `operators_bypass_moderation: false`, operators receive no automatic bypass and live operator chat is moderated. Without a compatible provider, bypass nodes default to false. Servers that deliberately enable operator bypass can configure its separate fallback level.

## Storage and privacy

Mutable data is kept under `<world>/chatautomod/`:

```text
state.json
state.json.bak
logs/automod-YYYY-MM-DD.jsonl
```

State schema version 2 persists temporary and permanent mutes, unexpired score entries, crossed-threshold state, retained violation history, and last known player names. Version 1 snapshots remain loadable and are migrated on the next save. Short-term spam/similarity buffers and inspect preferences are not persisted.

State snapshots are immutable, debounced, and written away from the chat path. Writes go through a temporary file, preserve a backup, and use atomic replacement where the filesystem supports it. A damaged primary snapshot falls back to the backup, and shutdown flushes the final state and pending audit records.

Logs use bounded JSON Lines output and configured retention. Original chat text is omitted from persistent state and logs unless its storage option is explicitly enabled. Control characters and line breaks are sanitized before logging.

## Project structure

- `core` contains the Java 21 configuration, normalization, detection, score, state, and persistence logic. It has no Minecraft or loader imports.
- `fabric-1.21.1` contains the official-Mojang-mapped Minecraft 1.21.1 event, command, permission, lifecycle, action, persistence, logging, and component integration.

The legacy `fabric-26.2` source remains in the repository but is not part of the 1.21.1 release build or required CI workflow.

Private-message command interception, message replacement, webhooks, a client interface, arbitrary regular expressions, IPv6 advertising checks, and other loaders are outside this release.

See [MANUAL_TESTING.md](MANUAL_TESTING.md) for the dedicated-server smoke checklist.
