# Easter eggs (spoilers)

Rules: subtle, discoverable, no confetti, never blocks a task. Themis lists them under
"Secrets" only after three are found (`Eggs.secretsOpen`); found ones are kept in the `eggs` prefs.
Code: `ui/Eggs.kt` (state and the pure, tested parts), tests in `test/.../ui/EggsTest.kt`.
Art: existing assets and vector icons only (the Athena profile, the Metis coin, the Laurel icon).

| # | Where | How | What happens |
|---|-------|-----|--------------|
| 1 | Olympus | Tap the title 7 times quickly | Haptic thunder roll; the saffron accent turns lightning white for 10 s |
| 2 | Olympus | Open Zeus between 3 and 4 AM | Subtitle: "The owls are judging you." |
| 3 | Themis, About | Long-press the Zeus coin | It flips to Athena's profile with "Know thyself" or "Nothing in excess" in Greek |
| 4 | Themis | Long-press the "Plain names" row | Hidden alternate subtitles: Homeric-style epithets ("Many-ridged, snowy Olympus", "Laughter-loving, golden Venus"). Long-press again to go back. A normal tap still toggles Plain names |
| 5 | Delphi (empty) | Long-press Athena's profile | One of Athena's own lines under her, a new one each press ("The owl of Minerva flies only at dusk...", "Strategy beats strength. Ask Ares how that went.") |
| 6 | Delphi | Send exactly "Who are you?" | The oracle answers in a riddle, on the phone, no model call. Once ever |
| 7 | Delphi | "Ask Annabeth ..." | A Zeus note: "Wise Girl has a plan. She always has a plan." (PJO). The request still goes to Annabeth |
| 8 | Pantheon | Tap Metis's coin 3 times quickly | Her line becomes "Zeus swallowed her whole. She has advised him from the inside ever since." for 6 s |
| 9 | Chronos | Swipe up, up, down, down on the header | The kicker shows today in Attic numerals ("ΔΠΙΙΙΙ October ΧΧΔΔΠΙ") for the rest of the day; repeat to turn off |
| 10 | Chronos | the owner's birthday | A small laurel beside "Today" in the header |
| 11 | Praxis | Nothing open anywhere | "Even Heracles rested." |
| 12 | Lethe | Open it | One slow ripple across the header and "What's forgotten here was finished." |

Notes
- #10 needs the date: his birthday is not recorded anywhere in the app. It shows when the calendar has an
  all-day "My birthday" or "The owner's birthday" event that day.
- Not built: the Fates' "The thread runs long" birthday card and the laurel border on Olympus;
  a match-day number on the Olympus coin for a followed team (Olympia's lane).
