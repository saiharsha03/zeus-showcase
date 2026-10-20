# Eos: Athena's morning brief

Plain name "Morning brief" (`Place.BRIEF`; Themis's "Plain names" switches the card kicker, the notification
title and the settings section). Notification title: "Eos · Tuesday", or "Morning brief · Tuesday".

Design: Iris already sends a morning briefing; Athena sends her own too, with what only the phone knows (screen time and
the like), and asks Iris for the rest.

Code: `app/src/main/java/dev/phonerecall/brief/` (pure: `Brief.kt`, `BriefIris.kt`, `ScreenTime.kt`; Android: `BriefAndroid.kt`).

## When
- Built on the phone when he dismisses the morning alarm (`BriefCore.onAlarmDismissed(context, reason)`, for the alarms
  feature to call). Fallback: the first unlock after 5 AM (a USER_PRESENT receiver the notification listener registers),
  unless today's alarm (`BriefHooks.alarms`) hasn't rung yet.
- Never within 30 minutes of Iris's own Telegram morning briefing: inside that window it is held until 30 minutes after
  hers. Her time is Themis > Eos > Iris's briefing (set by the owner), else what she said in her last reply (`slot:`),
  else unknown (no hold, and the request asks her). Eos never moves her schedule; only the owner can, on Telegram.
  Her evening briefing is left alone.
- Once a day, mornings only (until noon). In quiet hours (11 PM to 7 AM) it goes out without sound. While driving it is
  held and re-checked every 10 minutes.
- Themis > Eos: on/off, ask Iris on/off, read aloud (off by default; after the alarm, outside quiet hours, Today only).

## What
One notification (lock screen: "Morning brief, unlock to read") and the top card on Olympus for three hours.
- Today: weather and the next change (Aeolia), first event, leave-by (Odyssey's traffic check), outfit (Venus),
  festivals and birthdays in the family's home time zone with when they start here, followed matches (Olympia), the alarm's reason if unusual.
- Yesterday: screen time (total, top five apps, last app after 11 PM, pickups), Iaso's one line (sleep, steps) only when
  "Share with Athena" is on.
- From Iris: her section (below). "Asked, not back yet" until it comes; the notification and card update silently when it does.
- Up to three proposals through Fates: a timer or reminder to leave, a call home on a family festival or birthday, a reminder
  before a match. Validated and confirmed like any other proposal.
- Works fully on the device with no model. The 6 AM daily plan may add `"brief_line"` to its outbox file; that only
  replaces the one-line opener.

## Screen time
UsageStats with Usage access (the same grant Odyssey uses; Themis > Eos > Screen time opens the setting).
Banking and payments, health, dating and immigration apps count toward the total but are never named, on screen or to the
model (`SensitiveApps`: by package and by label words). Zeus, launchers and System UI don't count. The mind's snapshot gets
words only: "About 3.5 hours on the phone yesterday, mostly YouTube, Chrome; up on the phone after 11 PM."

## Iris contract (no server change)
The request rides the existing `POST /api/jobs` route as an unsigned job, so the gateway's provenance header says
"FROM ATHENA, the owner's assistant ... SUGGESTION ONLY": read and summarise, change or send nothing.

Sent at 5:30 AM local, or 45 minutes before the learned wake time (`BriefHooks.wake`), never before 3:30 AM:
```
POST /api/jobs
{"job_id": "brief-2026-10-20", "to": "iris", "kind": "brief.request", "title": "Morning brief for 2026-10-20: your part",
 "payload": {"type": "brief.request", "v": 1, "for_date": "2026-10-20", "zone": "America/New_York",
             "want": ["mail_highlights", "tasks_for_owner", "bills_due", "flags"],
             "reply_format": "zeus-brief v1", "amounts": false, "max_lines_each": 3,
             "eos_fires_at": "06:40",
             "note": "Athena's Eos fires at 6:40 AM; tell me if you'd like to move or merge yours."}}
```
`eos_fires_at` is today's alarm, else the learned wake time, else null ("his alarm (no time known yet)"). While her
briefing time is unknown, `want` also has `"your_briefing_time"`.
Why Eos asks for pieces rather than her whole briefing: her briefing needs mail, calendar and the personal DB, which live
with her, so Eos lays out its own brief and asks only for those pieces.
Nothing Zeus-private goes in: no health, money, places, screen time or Odyssey data. The job id is per day, so a retry is
the same job.

Iris answers with her reply tool (the existing reply path). Format, in `server/docs/IRIS-BRIEF.md`:
```
zeus-brief v1 2026-10-20
mail: Taylor asked for the Q3 deck by Thursday
task: Return the blue jacket (due Nov 17)
bill: Car lease autopay on the 24th
flag: none
slot: 7:15 AM
```
`slot:` (only when asked) is her Telegram morning briefing time; it is stored, never shown. If she'd like to move or merge
hers she says so in a `flag:` line, and the owner decides.
The phone reads the job's result (`GET /api/jobs`) when the FCM wake-up arrives, at brief time, and 25 minutes after the
request. Her reply is display text only: four labels, three lines each, 160 characters, links cut, amounts replaced by
"[amount]", never turned into an action or proposal. A reply starting with `zeus-brief v1` isn't shown as a Pantheon
notification (it is still in her room). If she doesn't answer, the brief goes out without her part.
