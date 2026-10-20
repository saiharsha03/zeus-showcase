# Kairos (plain name: Moments)

*"The right moment."* Kairos notices an everyday moment and offers the obvious helper. Everything it does is a
proposal (proposer "Kairos"). Only Fates.confirm, or a kind the owner put on auto-approve, runs anything. Code lives in
`app/src/main/java/dev/phonerecall/moments/`.

## What it does
**Cooking (the headline)**
- **Snap the box:** Themis → Kairos → Snap the box, or "Snap" on the dinner card.
  1. The camera or a picked photo is read by ML Kit text recognition on the phone. The photo is deleted after it's read.
  2. `CookParser` finds the method variants (oven, convection, toaster oven, air fryer, stovetop, microwave), the °F/°C
     temperature, time ranges, "preheat", the step in the middle (stir, flip, rotate, shake, or the box's own words
     such as "Remove film"), "let stand", and alternatives (pan sizes, wattages).
  3. It picks the oven by default and shows the alternatives.
- **The plan:** one bundled card. Every timer starts at Confirm, so everything is offset by the preheat:
  - preheat (dropped if the oven's already hot)
  - a check 2 minutes before the low end of a range
  - done at the low end
  - the midway step
  - the stand time
- **Ask Athena:** used only when the rules find nothing and the owner taps it. Haiku rewrites the box text into plain lines
  (through NetGate and the spend cap), and the same rules read those.
- **Recipe on screen:** when the assist sheet's screen text looks like a recipe, it offers "Timers for these steps"
  (back to back). Read on the phone; nothing is sent.
- **Spoken or typed:** Delphi's `suggest_timers(text)` covers cooking, laundry, tea and workouts. Without a length it
  uses a small table of common items, labelled "(a guess)", or asks him how long.
- **Dinner from the freezer:** a frozen or bake item in a receipt or order notification (name only), while he's at
  home between 5 and 9 PM, shows "Frozen pizza tonight? 425°F · 16–20 min" with Use guess / Snap / Later / Never.

**Context cards (one at a time, most urgent first)**
| Kind | When |
|---|---|
| Parking meter | Parked after a drive, walked away, not at home, work or a place with its own lot. Offers 1 h or 2 h, and warns 5 minutes before. |
| Leaving home | Up to 30 minutes before his learned departure (or a drive just started from home), with lights on (Hestia) and/or rain later (Aeolia). "Lights off" is one bundled proposal. |
| Charge before you leave | Battery under 25%, not charging, departure within 60 minutes. |
| Charge before bed | Battery under 30% after 9 PM, with an alarm before 8 AM tomorrow. |
| Snow and ice before the commute | 6–10 PM, when tomorrow's forecast has snow or freezing rain in the 3 hours before the first commute. `MorningHazardSource` (`MomentsCore.hazards(c)`) is exposed for the bedtime/alarm code. |
| Trash night | Asks "Is tomorrow trash pickup?" on up to 3 evenings, one a day. After that it reminds him at 8 PM the evening before, with "Remind 9 PM". |
| Dinner from the freezer | See above. |
| The usual call home | A weekday and time he got family calls in at least 3 of the last 6 weeks. Shows their local time; WhatsApp opens on his tap. |
| Staples running low | A Groceries item ticked bought at least 3 times at a steady pace, due again and not on the list. "Add it" is an add_to_list proposal. |

**Explicit only (he asks or snaps)**
- laundry (washer timer + "dryer?" reminder), tea, workout intervals
- a parking sign → meter
- a flight confirmation → event + check-in reminder T−24 h + airport leave-by (T−2.5 h minus his usual drive to the
  airport when Odyssey knows it)
- a flyer or ticket → event + leave-by
- a receipt photo → Moneta's manual entry, prefilled; he fixes and confirms
- copied text, read only in the assist sheet while it has focus:
  - a tracking number (UPS, USPS, FedEx, Amazon) opens the carrier page in the browser
  - an address opens Maps, plus a leave-by when the copy has a time

**Grocery aliases:** learned only from his corrections. When he edits a list proposal ("garbanzo beans" → "chickpeas"),
both names mean his choice from then on. Staples running low and Kairos's list proposals group by it.

## Quiet rules
- At most one Kairos card, and none while a Kairos proposal is waiting.
- Never in quiet hours (11 PM–7 AM).
- While driving, only Leaving home and Parking.
- "Not now" (or "Later") snoozes that kind for the day.
- Three in a row rest it for a week (Iris: three asks is the ceiling).
- "Never" turns it off until he turns it back on in Themis → Kairos.
- A moment he took or waved off doesn't come back.

## Auto-approve
- New kind **"Timers under 1 hour"** (`short_timer`, tier 2, off by default, listed in Themis → Auto-approve).
  - Only Kairos makes it; it isn't a model tool.
  - An hour or longer validates as an ordinary timer.
  - A plan runs automatically only when every checked step is a short timer.
- Everything else stays a confirm.

## Stubbed or not done
- Watch battery for "Charge before bed": there's no Wear battery bridge on this branch.
- Call home only sees calls that post a notification (incoming or missed WhatsApp/phone calls from people saved as
  Mom, Dad and so on). Ongoing calls are filtered out by the senses, and there's no call-log permission.
- Grocery aliases aren't applied to snap-the-box or receipt item names yet (lists and staples only), and only list
  edits teach them (no OCR or voice correction UI yet).
- Leaving home's "rain later" uses Aeolia's next-change line and today's rain chance, not an hourly window.
- The parking "paid area" is a guess (away from home, work and places with their own lot), not map data.
- Store-list purchases (ticking a frozen item off Groceries) don't feed the dinner card; receipts and order
  notifications do.

## TODO (from the 10-20 list, not built)
9. Leftovers from Iaso food logs: an "eat by" card after 3 days.
10. Car: oil change due by Odyssey miles since the last logged change (ask once for date/miles); car wash after a week
    with 3 or more snow/salt days.
11. Birthday gift nudge 7 days before for close people (People graph priority); the proposal is a task.
12. A meeting starts while Odyssey detects driving → "Join by phone?" (the event's dial-in, else open the event).
13. A followed Olympia match overlaps a meeting → after the meeting, a quiet, spoiler-free "catch-up" card.
