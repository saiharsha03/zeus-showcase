# Horae (rhythms)

Horae ("The hours, in their order"; plain name Rhythms) learns when the owner opens his apps and who he calls when. At the
usual time it offers the call home with one tap. Everything stays on the phone, in `rhythms.sealed` (its own Aegis
Keystore key, no-backup storage). Code: `app/src/main/java/dev/phonerecall/rhythms/` and `calls/`. Page: Themis > Horae.

## What it learns

| What | From | Rule |
|---|---|---|
| App habits | Usage access (`UsageStatsManager.queryEvents`, Odyssey's permission) | Up to 4 weeks, learning only after 2 weeks of data. Only apps used on 3 or more days a week; a window that held on half its weekdays. A session before 4 AM counts for the night before. |
| Skipped sessions | The same sessions | Stored, never notified. |
| Call habits | Call log (READ_CALL_LOG, asked on the page) and WhatsApp's call notifications (the senses listener) | The same person or group at about the same time (±30 min): daily on half the days, or weekly on a weekday it held on 3 weeks. |
| Awake window | When the WhatsApp notifications of someone he calls abroad arrive (times only) | Half-hour bins over 4 weeks; the longest quiet stretch of 4 h or more is sleep. |

Never learned: banking and Moneta's apps, password managers and authenticators, health, dating, immigration, system
apps. He can keep any app out ("Never learn") or let one back in.

## Time zones

A call habit is stored in the person's zone: Mom is `07:00 Europe/Lisbon` (an example), never a local time. The local
time is worked out each day, so either side's clock changes move the nudge by themselves. Zones are always `ZoneId`s,
never fixed offsets.

## The call-home nudge

One exact alarm, set again after every change. At the usual time, if he hasn't called since 90 minutes before it:
"Call home? It's 7:00 AM in Lisbon", with WhatsApp video, Voice and Later. Swiping it away means Not today. The
notice and the in-app card also show his things to tell and any weather extreme at home.

- One reminder 45 minutes later if he didn't answer, then nothing. Later waits 30 minutes, at most twice.
- Nothing if he already called, while they are usually asleep, or 3 hours past the usual time.
- Quiet hours (11 PM to 7 AM here) wait for the morning; a drive waits until it is over.
- The call buttons open `CallActionActivity`: a PLACE_CALL proposal from Horae, confirmed by that tap, then WhatsApp.

## Calls (ported from PhoneRecall)

Numbers in the family's country call on WhatsApp, video first, falling back to voice. Local numbers are a phone call
(the dialer opens with the number, because Zeus has no CALL_PHONE). A bare national number is read through the
contact's WhatsApp JID. Delphi's `place_call` only proposes, and calls are never auto-approved. The title Delphi gets
back has his own words and, at night there, "(1:30 AM their time, usually asleep)".

## Things to tell

Delphi's `bookmark_for(person, note)` (a proposal) or the quick-add on the call row. They show on the call-home nudge
and card right before the call, and he ticks them off after.

## Weather at home

Every 6 hours at most: Open-Meteo's daily forecast for the family's city, through Aeolia's door (`WeatherNet`). Only a
heatwave (42°C and up) or heavy rain (64.5 mm and up in a day) becomes a "Check on home" card.

## What the brain gets

Words only, once a day: "usually games every night ~9:45–11 PM", "calls Mom daily ~7 AM her time",
"Mom usually up 6:30 AM–10:30 PM her time". A real name becomes "someone"; family words like Mom stay. Never a number,
a JID, a raw session or a notification's text.

## For other parts of Zeus

`Rhythms.signal(context)` returns a `HabitSignal`: `during(at)`, `shouldHold(at, urgent)` and
`bedtimeLine(night, earlyStart)` ("Skip the game tonight?").
