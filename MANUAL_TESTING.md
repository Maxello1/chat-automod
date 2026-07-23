# Minecraft 1.21.1 dedicated server smoke checklist

Run these checks with Java 21 on a temporary server. Keep a backup of its world and configuration, and never reuse a production world for destructive persistence checks.

## Build and prepare

Run the focused release checks:

```text
./gradlew :core:test
./gradlew :fabric-1.21.1:build
```

- Confirm `fabric-1.21.1/build/libs/chat-automod-fabric-1.21.1-<version>.jar` exists and no separate core jar is required in the server's `mods` directory.
- For a Loom development server, run `./gradlew :fabric-1.21.1:runServer`. Review the Minecraft EULA and set `fabric-1.21.1/run/eula.txt` only if you agree to it.
- For a standalone smoke server, install Fabric Loader and the matching Fabric API for Minecraft 1.21.1, then add the release jar.

## Startup and compatibility

- Start Minecraft 1.21.1 with Fabric Loader, Fabric API, Chat AutoMod, and Java 21.
- Confirm the log lists Chat AutoMod without entrypoint, linkage, or mixin failures.
- Confirm startup creates `config/chatautomod/automod.json`.
- Confirm startup creates all default files under `config/chatautomod/filters/`, including `exceptions.json`.
- Confirm startup creates the world `chatautomod` data directory and can write its state and log directories.
- Join with an unmodified client that does not have Chat AutoMod installed.
- Send an ordinary message and confirm every player receives the original signed message exactly once.
- Stop and restart cleanly before continuing so startup loading and shutdown flushing are both exercised.

## Permissions and bypass

- As an operator at the configured command fallback level, confirm the administrative commands are available.
- As an OP level 4 player with `operators_bypass_moderation` disabled, run `/automod permissions <player>` and confirm every live-chat bypass is reported as disabled.
- Send a known violating chat message as that operator and confirm it is moderated even though `/automod test` is available.
- As a non-operator without explicit permissions, confirm administrative commands are rejected.
- With a compatible permission provider, grant full bypass and each category bypass separately; confirm only the granted categories are skipped in live chat.
- Enable `operators_bypass_moderation` deliberately, reload, and confirm the configured bypass fallback restores the old operator-bypass behavior. Disable it again afterward.

## Filters, blocking, and alerts

- Configure a harmless temporary filter term and reload.
- Send a matching message and confirm it is not broadcast.
- Confirm the sender receives the configured warning only once.
- Exercise a default rule with uppercase, mixed case, repeated spaces, punctuation separators, compact spelling, leetspeak, a zero-width character, and a basic Unicode lookalike.
- Confirm `I am gay`, `gay rights`, `gay marriage`, and `the character is gay` remain allowed by default.
- Confirm `night`, `nights`, `bigger`, `trigger`, `Nigeria`, `Nigerian`, `Niger`, `assignment`, `signal`, and `original` do not trigger the severe built-in matcher.
- Confirm only players with `chatautomod.alerts` receive the staff alert.
- Disable original-message display and confirm the alert does not expose it.
- Enable original-message display deliberately and confirm the alert includes sanitized content.
- Use the alert's History and Mute controls and confirm permission is checked when the control is clicked.
- Toggle `/automod inspect` and confirm only that staff member's alert detail changes.

## Spam and scoring

- Trigger rapid spam, an exact duplicate, and a slightly modified repetition separately.
- Trigger a direct domain, an obfuscated domain, a valid IPv4 address with a port, and a Discord invite separately; confirm advertising decisions and allowed-domain boundaries.
- Confirm each command/test result shows the correct stable rule ID.
- Cross several score thresholds with one message and confirm only the highest crossed action runs.
- Send another violation while above that threshold and confirm its threshold action does not repeat.
- Let enough entries expire, cross the threshold again, and confirm it can run again.

## Mutes and actions

- Run `/automod mute <player> 30s test` and confirm later chat is blocked without adding points.
- Hold the chat key and confirm mute notices respect their cooldown.
- Apply `/automod mute <player> permanent test` and confirm the permanent notice has no remaining-duration text.
- Run each `/automod clear` scope while a mute is active and confirm none removes the mute.
- Restart once with a temporary mute and once with a permanent mute; confirm both survive.
- Confirm temporary expiry and `/automod unmute` restore chat, and that unmute removes either mute kind.
- Trigger a configured severe rule and confirm its block, staff alert, score, history, and permanent mute occur once.
- Test kick and configured command actions separately and confirm each executes once.

## Commands and persistence

- Run `/automod` and confirm version, readiness, enabled state, active-rule count, and tracked-player count are accurate.
- Run `/automod test <message>` twice and confirm score, recent messages, history, logs, snapshots, mutes, and inspect preferences do not change.
- Run the test as a player with live bypass and confirm the message is still evaluated while the bypass status is reported.
- Test a severe sample and confirm the command never punishes the executor or another player.
- Exercise `/automod history` and `/automod violations` with multiple pages and with message storage both disabled and enabled.
- Exercise `score`, `history`, `spam`, and `all` clear scopes separately and confirm unrelated state is preserved.
- Introduce errors in the main configuration, a filter pack, and the exceptions file, then run `/automod reload`; confirm every useful file and JSON path is reported and prior behavior remains active.
- Restore valid JSON, reload, and confirm the complete replacement applies together.
- Create violations, restart, and confirm scores, threshold state, history, names, and both mute kinds load while duplicate/similarity buffers do not.
- Load a version 1 state fixture and confirm it is accepted, its old mute becomes temporary, and the next save uses schema version 2.
- Corrupt a test copy of `state.json` while retaining `state.json.bak`; restart and confirm the backup is loaded without losing the server startup.
- Interrupt a disposable test server during a save and confirm the primary or backup state remains readable on the next startup.

## Privacy and retention

- With original-message storage disabled, inspect the state snapshot and JSONL files and confirm chat bodies are absent.
- Enable original-message logging deliberately and confirm sanitized single-line output.
- Place an expired daily log in the logs directory, restart, and confirm retention cleanup removes it.
- Stop the server after queued violations and confirm the final snapshot and pending log records are flushed without executor-shutdown errors.
