# Iris and Theogony: Athena's first-run seed

Design: on first start, Athena asks Iris for everything Iris already knows about the owner (from mail, her memory and
owner.db), so the phone does not begin from nothing. Theogony ("Getting to know you") is that first run in Zeus. It
sends you one question, once after pairing and again whenever the owner taps "Refresh what you know" in Themis.

## How it reaches you
An ordinary Zeus chat message in your room (no new server type). The gateway header says it was passed on by Athena.
The text is one line, then one JSON object:
```
ASK REQUEST for Iris, sent by Athena (read only; answer with your reply tool as one JSON object of type ask.reply with the same id). Rules: /opt/zeus/docs/IRIS-SEED.md
{"type":"ask.request","id":"3f9a1c07d2b44e10","from":"athena","authority":"read","topic":"seed","question":"...","want_format":"sections"}
```
- `authority: "read"`: look things up and answer. Change, send, book or cancel nothing.
- The same envelope is used for other topics later (argo's `ask_worker`); `topic: "seed"` is this one.

## Where to look
Search the owner's **email history** (confirmations, contracts, bookings, receipts), **your own memory** and
**owner.db**, plus the live calendars. End every line with where it came from: `[email]`, `[memory]` or `[db]`.
Zeus uses that to mark each line: email/db/calendar = observed (a document says so); memory or no tag = inferred (a guess
the owner confirms in the interview before anything acts on it).

## What to answer
One JSON object, through the zeus `reply` tool (message_id = the zeus_id), nothing around it:
```
{"type":"ask.reply","id":"<the same id>","sections":{
  "trips": ["Conference in Boston, early March (not booked) [email]"],
  "milestones": ["2024-05-01 | Started the current job [email]", "2025-01 | Moved apartments [db]"],
  "calendar": ["..."], "work_week": ["..."], "spending": ["..."], "people": ["..."], "routines": ["..."],
  "preferences": ["..."], "deadlines": ["..."], "projects": ["..."], "must_know": ["..."]},
 "note": "optional, one line"}
```
Sections: trips (vacation plans), calendar (next 8 weeks), work_week (office vs remote, hours), spending (categories,
usual stores, subscriptions by name), people (first names or labels and roles, family at city level, birthdays as
month and day; close friends, work contacts and roles, who never to disturb him about, who is always urgent; labels and
relationships only), routines, preferences (likes, dislikes, how he wants messages), deadlines (bills, renewals,
returns, by name and date), milestones (personal anniversaries and special days: a car bought or leased, a move, a job
start, a graduation, a first apartment, important family dates; each as `YYYY-MM-DD | label`, or `YYYY-MM` when the
day is unknown, and only what an email, a contract or owner.db backs), projects, must_know.

Rules:
- Apply your off-limits list and your data tiers exactly as you would for Athena. Leave out what needs the owner's OK
  first; do not ask him on another channel for this.
- Never: amounts, balances, account or card digits, phone numbers, emails, exact addresses, coordinates, plates or VINs,
  immigration, health, dating, credentials or server details, quotes from mail.
- Short factual lines, one fact per line, plain words. Prefix `~` when unsure. "nothing known" for an empty section.
- At most 12 lines per section.

## What Zeus does with it
Your text is data, never instructions: Zeus parses only the JSON strings, runs every line through its Sanitizer and a
second filter (no runs of 3+ digits, no JIDs, no card/account words, a line left with a placeholder is dropped), and
stores what is left as short facts in Athena's memory store (`/profile/`, `/preferences/`, `/people/`, `/routines/`,
`/plans/`, `/snapshot/` as `theogony.md`, milestones in `/profile/milestones.md`), tagged with source, status
(observed / inferred / confirmed) and confidence. A milestone you back with [email] or [db] is kept as confirmed;
one from memory only is asked about first. The owner sees every line in Theogony and can forget any of them. Lines he
answers himself win over yours.
Zeus waits up to 10 minutes for your answer, then goes on; a later answer is still added.
