# Minecraft 26.2 dedicated server smoke checklist

Run these checks on a temporary server and keep a backup of its world and configuration.

## Startup and compatibility

- Start the target Fabric server with Chat AutoMod and Fabric API installed.
- Confirm startup creates `config/chatautomod/automod.json` and the world `chatautomod` data directory.
- Join with an unmodified client that does not have Chat AutoMod installed.
- Send an ordinary message and confirm every player receives the original signed message exactly once.

## Blocking and alerts

- Configure a harmless temporary filter term and reload.
- Send a matching message and confirm it is not broadcast.
- Confirm the sender receives the configured warning only once.
- Confirm only players with `chatautomod.alerts` receive the staff alert.
- Disable original-message display and confirm the alert does not expose it.
- Use the alert's History and Mute controls and confirm permission is checked when the control is clicked.

## Spam and scoring

- Trigger rapid spam, an exact duplicate, and a slightly modified repetition separately.
- Confirm each command/test result shows the correct stable rule ID.
- Cross several score thresholds with one message and confirm only the highest crossed action runs.
- Send another violation while above that threshold and confirm its threshold action does not repeat.
- Let enough entries expire, cross the threshold again, and confirm it can run again.

## Mutes and actions

- Run `/automod mute <player> 30s test` and confirm later chat is blocked without adding points.
- Hold the chat key and confirm mute notices respect their cooldown.
- Restart the server while the mute is active and confirm it remains active.
- Confirm expiry and `/automod unmute` both restore chat.
- Test kick and configured command actions separately and confirm each executes once.

## Commands and persistence

- Run `/automod test <message>` twice and confirm score, recent messages, history, logs, mutes, and inspect preferences do not change.
- Introduce multiple invalid configuration values and run `/automod reload`; confirm all useful JSON paths are reported and prior behavior remains active.
- Restore valid JSON, reload, and confirm the complete replacement applies together.
- Create violations, restart, and confirm scores, history, names, and mutes load while duplicate/similarity buffers do not.
- Interrupt a test copy during a save and confirm the primary or backup state remains readable on the next startup.

## Privacy and retention

- With original-message storage disabled, inspect the state snapshot and JSONL files and confirm chat bodies are absent.
- Enable original-message logging deliberately and confirm sanitized single-line output.
- Place an expired daily log in the logs directory, restart, and confirm retention cleanup removes it.
