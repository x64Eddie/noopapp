# Phone screen log (personal build, instrumentation)

A wrist sensor cannot tell lying awake in bed from sleeping, so a night spent reading on a phone is staged
as sleep once the wrist goes still. The phone knows when its screen was on. This log keeps that, so a night
can be compared with it. Nothing reads it for scoring yet.

## What is stored

One row per event: a unix timestamp and an event type. Nothing else.

| Event | Meaning |
|---|---|
| `PHONE_SCREEN_ON` / `PHONE_SCREEN_OFF` | The screen turned on or off |
| `PHONE_UNLOCK` | The lock screen was dismissed, so the phone was actively used |
| `PHONE_LOG_START_SCREEN_ON` / `_OFF` | Written each time logging starts, because the state before the first event is unknown |

Never stored: app names, screen content, notifications, location, or anything about what the phone was used for.

## Where it lives

- In its own small database file, `noop_fork.db`, inside the app's private storage. It is separate from the
  main database so upstream's migrations are never touched.
- Rows older than 120 days are deleted when the app starts.
- It leaves the phone only inside an export you choose to share: Settings, "Export raw sensor data", or the
  7-night zip. The rows appear as `event` rows with the labels above.

## Limits

- Events are only seen while the app process is alive. The connection service keeps it alive while a strap is
  connected, so a gap is a gap and is not guessed.
- It does not see a paper book or a tablet.
- Removing it: delete the `ScreenStateLog.start` line in `NoopApplication.onCreate` and the app stops recording.
