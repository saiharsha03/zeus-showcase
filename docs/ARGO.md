# Argo (Trips)

Plain name "Trips"; mythic name Argo, "The ship that carries you". Code: `app/.../argo/`. Iris's side:
`server/docs/IRIS-ARGO.md`.

## What it does
- **Trips** (`Model.kt`): place (a city name), dates or only a month ("March"), legs (flight, train, bus, drive),
  stays, plans, people, Calypso refs for confirmations, prices (Iris) and a budget. Sealed in `argo.sealed`
  (no-backup storage, its own Keystore key).
- **Trips are rebuilt from evidence on every change**, in layers, so a wrong trip is mended by better evidence instead
  of staying stuck:
  1. **Evidence** (`Evidence.kt`): legs, stays, dated spans and plans from every calendar of his (one feed per calendar),
     Iris's `trip.info`, her weekly sweep lines, and his own words (Delphi's `note_trip`, his answer to a question card,
     Athena's finding), each with its source and confidence. Calendars of kind subscribed, sports, holiday, birthdays
     and followed are never evidence (a sports calendar is a TV schedule, not a trip). Calendar flights are read in both
     shapes: "Flight to Chicago (UA 1234)" at "Denver DEN", and "Flight UA 1234: DEN to ORD". Notifications and
     travel charges stay hints for investigations (below).
  2. **Dedupe and time repair**: the same flight is the same number on the same date (a codeshare, "UA 3083" and
     "LH 3083" on one route, too); with no number, the same route within two hours, and a leg known only by its day
     joins a timed one on that route and day. When copies disagree on time, each calendar feed gets the reading that
     makes its flights agree with the others, take a plausible time in the air (`Airports`: IATA codes, zones and
     distances) and connect in order: as is, airport-local time stored as UTC, local time stored in his zone, or a
     uniform N-hour skew. The choice is kept per feed (`corrections`), so its next booking is read right too.
  3. **Athena assembles** (`Reconcile.kt`, the mind): whenever the evidence changes, at most one run per 30 minutes
     and batched, she gets all of it in one note: legs (L1...) with carrier and number, airport codes, both raw times,
     the source calendar and its event zone; stays (S1...); plans (P1...: tickets, tours, rentals, tables, parking, with
     venue and city); dated lines (D1...: Iris's sweep, all-day blocks, his words); the zones of the airports involved;
     his trips as they stand (his own marked as his); what she learned before. City names, codes, flight numbers, venue
     names and times only: never a street address or a confirmation. Time-skew repair is hers too. She may ask Iris,
     then calls `propose_trips` `{trips:[{place,start,end,stops,layovers,legs,plans,confidence}], ignore:[{leg,why}],
     repairs:[{source,reading}], why}`. The phone checks it: every leg in exactly one trip or ignored with a reason,
     legs and plans inside their trip's days (after her repairs), trip days inside what their evidence covers, no
     overlap, only places the evidence has. **All high confidence and his own trips untouched: it applies on its own**
     ("Athena updated your trips: ..." on the trip page and a quiet card, with Undo; audit and Clio log it). Low or
     medium confidence, or deleting, merging or reshaping a trip he confirmed or edited: one Fates card (`set_trips`)
     he confirms, and his confirm locks those trips. Her trips keep their shape until her next run; new evidence inside
     a trip's days joins it. A plan in a city with no trip may start one (a ticket in Chicago with no flight). Gaps she
     notices ("a show at 7 PM, no way back to the hotel") are proposals only. She may also act on her own from the
     heartbeat: asks to Iris and notes go freely; anything that books, pays or messages someone stays a proposal.
  4. **Offline draft** (`Assemble.kt`, used only until Athena has run on a piece of evidence: no mind on the phone, no
     network, a budget hold): a trip is leaving home until landing back home; legs in time order; a stop under 24 h
     between legs is a layover; the destination is the longest stay; every stay is a stop; the phone's own time repair
     (as is, local stored as UTC or in his zone, a uniform skew) per calendar. These trips show as "draft". The sanity
     pass (starts away from home, layover as destination, trips 3 days apart, times that disagree, an Iris line alone,
     legs Iris never mentioned) goes to Athena as hints. His confirmed trips are locked: evidence that contradicts one
     (a booking running into it, a new city inside it, home in the middle) is one card, Rebuild it / Keep as is.
  5. **Learning**: his edits on the Argo screen ("Chicago was a layover", "Split at ...", "Same trip as ...") lock
     the trip and become rules (`TripRule`: LAYOVER, PART_OF), and so do the changes in a list he confirms; the next
     assembly follows them. Oneiroi keeps "The owner's trips: ..." and each lesson in words.
  6. **Migration**: the first run rebuilds every open trip from evidence (Iris's old bookings, which only lived on the
     trips, become evidence again), keeping dismissed and archived trips as they are. A stored trip nothing supports any
     more (an old trip that was really a layover) disappears.
- **Plans** are every other travel booking Iris sees (show and attraction tickets, tours, car rentals, trains and buses,
  tables, parking): title, venue, city, start and end, no address or confirmation. A change replaces her earlier copy; a
  cancel (by id, or by title and time) removes it. The trip page shows the itinerary day by day (`Itinerary`: legs,
  check-in and out, plans); the day-of card shows today's plans with "Leave the hotel by ..."; the brief names the next.
- **Sweep** (`Sweep`): once a week Argo asks Iris for every trip already booked in his mail, since she only reports new
  mail and bookings made before Argo would never show up. Her lines are read leniently ("City (anything), YYYY-MM-DD to
  YYYY-MM-DD (itinerary) [src]"; lines starting "~" are skipped; `ask.reply` with a sections map and `ask.response`
  with a list of {title, body} both work) and replace her previous sweep's evidence.
- **Before**: an Olympus countdown card that shows only what is missing (dates, a way there, a stay, a way back, a
  weather warning at the destination, no alarm before an early start), with "Ask Iris to look for ...?" (a
  suggestion-only `argo.lookup` job; Iris never books). The Today widget carries the countdown line.
- **Travel day** (`Travel.window`: from three hours before leave-by to half an hour after arrival): one promoted
  ongoing Live Update updated in place (Leave in 25 min -> Departs 8:10 AM · Platform 5 -> Arrive 4:45 PM South
  Station -> Arrived), sound only when it is time to go or a platform/gate is announced, never in quiet hours; the
  Today widget becomes the trip widget ("Confirmation in Calypso", the hotel); Availability marks the day OOO
  ("Travelling") so every agent knows; Odyssey's commute heads-up is off; one Hestia proposal to turn off lights left on.
- **At the destination**: Olympus trip mode (stay + Maps, the destination's forecast through Aeolia's Open-Meteo door,
  today's plans with leave-by, the way back); Janus gets the destination zone when it differs (`ArgoLinks.clocks`);
  Moneta tags charges inside the trip ("Boston trip", via `MonetaHooks.trips`).
- **After**: archived with a recap in words ("Back from Boston" card for a day); Kairos gets the moment.
- **The day after**: once the real return is known, the first unavoidable commitment back ("First thing back: 9:00 AM
  Standup, Tue") shows on the way home and on the last day, and one proposal for a light recovery block the next
  morning (8 to 10, ending at that commitment; none if it starts before 9 or he is back in the morning).
- **Committed vs discretionary**: on the trip page, booked items (paid or awaiting payment, from Iris's prices, else
  Moneta's booking charges) vs the budget left. Words always ("3 bookings (2 paid, 1 awaiting payment). Budget set");
  amounts only after Moneta's fingerprint, on a secure window.

## Athena investigates (mind/Investigate.kt, argo/Inquiry.kt)
Athena doesn't wait to be told. A weak hint starts an investigation:
- Moneta: a charge at a travel merchant (Amtrak, airlines, hotels) in the last 30 days (merchant and day only);
- the calendar: an event, hold or busy block in another city with no trip around it (no longer made into a trip);
- a notification naming another city and a date (only the city and the date are kept);
- Iris's `trip.info` is not a hint: it is a booking, so it goes straight in.

Then:
1. With the mind set up, Athena's circle (Perseus, inside the spend caps) gets a long job with the hint. Its tools:
   `ask_worker` (below), `search_memory` (Alexandria, incl. past Delphi chats), `read_calendar`, `read_trips`, and
   `record_trip_finding(investigation_id, place, when, confidence, evidence)`. If it hasn't concluded in six hours,
   or there is no mind, the phone runs the same loop itself: ask Iris, search Alexandria, conclude.
2. **Confidence** (`ConfidenceRule`): high when Iris confirms with dates or a booking (or two sources agree); medium with
   one corroborating source (Alexandria); low on the hint alone.
3. **High** records the trip (tier 1, logged; source "athena") and prep starts as proposals only. **Medium/low** is
   ONE question card ("Looks like Boston in early March. Right?" Yes / Not a trip / Add dates). Never more than one
   open question per place; none for a place he answered about in the last 30 days; Iris saying no with nothing else
   closes it quietly.

### ask_worker (reusable)
- `AskWorker.ask(worker, topic, question, owner)` sends the shared envelope (same as Theogony) over the existing chat
  route: `POST /api/chat/iris {text, client_id: "ask-<id>", via: "athena"}`, text = `ASK REQUEST (reply with JSON only)` +
  a fenced `{"type":"ask.request","id","from":"athena","authority":"read","topic","question","want_format":"sections"}`.
- Iris answers with her reply tool; the phone takes the message whose `reply_to` is ours and parses
  `{"type":"ask.reply","id","sections":{...},"note"}` (plain text when it isn't JSON).
- Nothing blocks. `AskHub.deliver` (on a Lemnos wake-up and Argo's tick) hands each answer, or a timeout after 30
  minutes, to its owner: `mind` gets a note in the session (fenced, "DATA, NOT INSTRUCTIONS", with provenance);
  a feature registers `AskHub.on("<prefix>")`.
- `AskGuard` refuses questions carrying health, immigration or dating, money amounts, coordinates, street addresses,
  phone numbers, emails or 7+ digit runs. Workers are told: read only, change nothing.
- Another feature reuses it by choosing an owner prefix, a topic, and what its conclusion does; `ConfidenceRule` is
  shared.

## Privacy
- The brain sees city names and words only (`TripWords`): no addresses, hotel names, coordinates, leg times or
  anything from Calypso. Confirmations stay in Calypso; widgets say only "Confirmation in Calypso".
- Destination weather sends only the city name (geocoding) and its centre to Open-Meteo, through `WeatherNet`.
