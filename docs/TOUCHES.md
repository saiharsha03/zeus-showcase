# Small touches (qol2)

One small helper card each, from signals Zeus already has. Code lives in `app/src/main/java/dev/phonerecall/qol2/`:
`Model.kt` (the touches, what he keeps, the context), `Rules.kt` (pure rules, tested in `TouchRulesTest`),
`Parse.kt` (cancellation notices, ticket links), `Touches.kt` (signals, taps, hooks), `TouchesActivity.kt`
(Themis → Small touches, and the photo and receipt pickers), `Receivers.kt` (timers, audio drop, the Read later share target).

## How it plugs in
- Home: `BoardInputs.touches`, card kind `TOUCH` (one id per touch, one instance per thing), action role `TOUCH` with
  payload `<TOUCH>|<key>|<option>`. At most three touch cards at a time. Not on widgets (not a widget kind); only the
  layover, ticket and running-timer cards carry a Now Bar line.
- Hooks: `Odyssey.visited` (a "when I leave" note fires), `MusicCore.driveEnded` (music at the drive's end),
  `SensesListener` (cancellation notices), `Attention.notify` (focus holds nice-to-have alerts for the brief).
- Themis: Small touches has a switch per touch; Horologion lists them as `touch.<NAME>` switches. Clio area `touches`.

## Quiet rules
Not in quiet hours (11 PM to 7 AM) except bedtime mode, timers, leave notes and layovers. On a call, driving or with a
game in front, only timers, leave notes and layovers. "Not now" snoozes a touch for the day; "Never" turns it off.

## What a tap does
Only reversible things (with Undo) or opening another app. Messages go out only through the share sheet he picks in.
A smart plug is a Fates proposal. Focus never touches the system Do not disturb unless he turns on "Match mode: Do not
disturb too"; bedtime mode uses Do not disturb only when Zeus has notification-policy access, else opens Samsung Modes.

## Needs from the owner
- Photos for family: the picker opens on recent photos, not the trip's window. Preloading by date needs `READ_MEDIA_IMAGES`.
- Bedtime mode and match DND: Do not disturb access for Zeus (Settings → Notifications → Do not disturb access).
- Audio reconnect: Nearby devices (BLUETOOTH_CONNECT), already requested for car detection.
- Health sync and light day: Health Connect steps and sleep, already granted for Iaso.
