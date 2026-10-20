# Models: which model each feature uses

Design: model choice is data, not code. `app/src/main/java/dev/phonerecall/models/Models.kt`.

- **Features** (defaults): Athena's mind (Sonnet 5.5), each protégé (Odysseus, Perseus,
  Arachne on Sonnet 5.5; Atalanta, Diomedes on Haiku 5.5; Annabeth on Opus 5.5), Metis (Fable 5.1, advisor),
  Delphi's bigger turns (Sonnet 5.5; quick turns stay Haiku), background triage (Haiku), Reflect (Haiku),
  Humanizer polish (Haiku; nothing calls the model step yet), Prometheus (Opus 5.5). Dream has no row: it is
  Athena's scheduled run, so it uses her model.
- **The list.** Each mind sync reads `GET /v1/models` (api.anthropic.com, already allowed) and caches it.
- **Pickers** (Horologion > Everything else > Models): only models that fit. Agents: anything listed and not
  deprecated. Metis: advisor-capable in the price table. Messages API features (Delphi, triage, Reflect,
  Humanizer): only models with a `BrainModel` price, so the spend ledger stays exact. Save and Undo each
  take his fingerprint. Agents get a new version on the next sync; the rest use it on their next call.
- **The card.** When the list has a model that is newer (same line, later) or cheaper (same line, lower price)
  than one in use, Athena posts one Fates proposal, SWITCH_MODEL: "<model> is out: <why> for <feature>.
  Switch?" with Switch (fingerprint) and Not now; Undo restores the old model. Each model is suggested once.
  The first sync only learns what is already out.
- **Prices** live in code: `BrainModel` (brain/Spend.kt) and `Prices.TABLE`. A new model that needs code (a price,
  a changed parameter, a beta) is Prometheus's job: it reads `docs/claude-api/models.md` and follows
  `docs/claude-api/model-migration.md` (copied from the claude-api skill), then opens a PR.
