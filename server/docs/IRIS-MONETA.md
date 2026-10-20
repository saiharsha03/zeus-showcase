# Iris and Moneta

Design: Iris logs every transaction to Moneta and may edit Moneta's view freely; every edit is logged and undoable.

Moneta is the owner's money view on his phone (Zeus). It is built from three things:
1. **Your money log.** Every row in owner.db `transactions` goes to Moneta on its own (the phone pulls it through
   the gateway, by id). Keep logging as you do: date, amount, category, description (the merchant), type, card,
   status. Only the last four digits of `card` ever leave; rows whose type or category says "balance" are skipped.
   Those rows are keyed `iris:<transactions.id>` in Moneta.
2. Bank notifications, read on the phone (Moneta folds a notification into your row when both saw the charge).
3. **Your edits**, with `zeus-moneta` (below). They apply directly on the phone, with no card for the owner to confirm.
   Each one lands in Moneta's change log ("Iris recategorized Grocer Mart to Groceries") and he can undo any of them.

## zeus-moneta (JSON in, JSON out)
```
zeus-moneta add '{"date":"2026-10-19","amount":47.82,"merchant":"Grocer Mart","category":"Groceries","last4":"1234"}'
zeus-moneta edit iris:812 '{"amount":50.10,"category":"Groceries"}'      # fields: date amount merchant category kind last4 currency note
zeus-moneta recategorize iris:812 "Eating out"
zeus-moneta merge iris:812 iris:813            # keep the first, fold the others into it (duplicates)
zeus-moneta delete iris:813
zeus-moneta budget Groceries 400               # monthly, dollars; "none" removes it
zeus-moneta sub-add '{"name":"StreamBox","amount":9.99,"cycle":"monthly","next":"2026-11-05"}'
zeus-moneta sub-remove StreamBox
zeus-moneta chart '<spec>'                     # pin a chart in Moneta's "From Iris" section
zeus-moneta chart-remove <id>
zeus-moneta schema                             # every op, field, kind and chart option
```
Answers: `{"queued": true, "op_id": "..."}` (add also returns the `key` to use later), or
`{"queued": false, "error": "..."}` saying what to fix. Amounts are dollars. `kind` is spend, refund, payment,
transfer, income or declined. Categories: Groceries, Eating out, Fuel, Rides and transit, Car, Shopping,
Subscriptions, Utilities, Rent and home, Travel, Fun, Health, Transfers, Other (other names are mapped).

## Charts
The phone draws charts itself from Moneta's ledger; you send a spec, never an image or HTML. A spec is at most 4 KB:
```
{"id": "dining-trend", "type": "line", "title": "Eating out by month", "subtitle": "Last 6 months",
 "query": {"metric": "spend", "by": "month", "months": 6, "categories": ["Eating out"]}}
```
- `type`: line, bar, stacked, donut or table.
- `query.by`: month, week, day, category, merchant or card. `split` (a second dimension, one series each): month,
  category, merchant or card. `metric`: spend (dollars) or count. `months`: 1 to 24 back from now. `top`: keep the
  biggest 1 to 12 (the rest become "Other"). Filters: `categories`, `merchants`, `cards` (last four), `kinds`.
  `cumulative`: running total (for burn-down). `reference: "budget"` draws his budget line.
- Pinning a chart with an existing id replaces it. At most 12 from you.

## Never
- Move money. Paying, transferring, cancelling or disputing is the owner's, in his bank's app; Moneta can only open it.
- Send a full card or account number (anything with 7+ digits in a row is refused), or an account balance.
- Ask for anything back: Moneta does not answer queries; your edits are one-way.
