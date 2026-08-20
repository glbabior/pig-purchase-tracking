# PigPurchaseTracking — working agreements

## Documentation upkeep (standing rule)

Any **functional** change — behavior, API surface, schema, or an invariant —
is not done until the docs have been checked against it:

- `QUICKSTART.md` — the two launch commands and how they differ, and the one-line
  description of each screen. It is on this list for the same reason
  `architecture.html` had to be added to it: a doc nobody is told to check is a doc
  that goes stale. It is also the **first** file a stranger reads, so it is the worst
  one to be wrong. Renaming a screen, adding one, or changing what `demo.cmd` sets up
  lands here.
- `README.md` — the API reference, the Working / Known gaps / Planned lists, and
  the `_Code-verified <date>_` stamp
- `docs/ARCHITECTURE.md` — diagrams, the layer map, and the invariants in §7
- `docs/architecture.html` — the **shareable** version of the same material, with
  rendered diagrams. It is the source `docs/ARCHITECTURE.pdf` is built from.

### The architecture document says everything twice. Update both.

`ARCHITECTURE.md` and `architecture.html` cover the same ground in two files with
their own prose and their own diagrams — nothing generates one from the other. Only
the markdown was in this checklist at first, so the HTML went **a week** out of date
without anyone noticing: long enough to show a status enum missing a constant, a
package table missing three types, and a privacy claim that had stopped being true.

### Rebuilding the PDF is part of running doc-drift

`docs/ARCHITECTURE.pdf` is **generated and gitignored** — a build output, not a
tracked file, so it can never be the stale copy that contradicts the other two.

Every `doc-drift` run ends with:

```
node docs/build/build-architecture-pdf.mjs   # rewrites docs/ARCHITECTURE.pdf
```

Run it after applying the run's findings, so the PDF reflects the corrected page
rather than the one the agent complained about. Run it even when the agent found
nothing in `architecture.html` — it is a few seconds, and the alternative is
deciding each time whether the file changed enough to matter.

It refuses to write the PDF if any diagram fails to render. Counting the rendered
diagrams is not enough on its own: mermaid answers a diagram it cannot parse with an
SVG containing a bomb icon and the words "Syntax error in text", so the count comes
back complete and the PDF looks finished. The check looks for that marker.

Needs `npm install --prefix docs/build mermaid@11` once on a fresh clone. The generator
and its dependencies live in `docs/build/` so that `docs/` holds only what a reader
wants; the PDF is still written to `docs/`.

**To commit a copy on purpose** — for a release, or to hand someone the file through
the repo — `git add -f docs/ARCHITECTURE.pdf`. Do that only when explicitly asked;
the default is that it stays untracked.

An early version of this page was once published as a Claude artifact. That artifact is
**abandoned** — the PDF in `docs/` is the shareable copy now. Do not republish it, do not
look for it, and do not treat anything it says as current. Its URL has been removed from
this file deliberately: it is a private link tied to one account, and this repo is
shareable.

Update whatever drifted. Move the `Code-verified` stamp only for what was
actually verified this session, not as a formality.

Skip only for changes with no functional surface — formatting, comments, build
config — and say the check was skipped rather than skipping silently.

Every documentation claim asserted or corrected must cite the `file:line` it was
checked against, so the claim can be verified without re-reading the code.

## Subagent reports

When the `doc-drift` agent (or any review agent) returns findings, write them to
`.claude/reports/<agent-name>.md`, **overwriting** the previous run — these are
disposable and gitignored. A subagent's report otherwise returns only to Claude
and is never visible in the project.

Write the report yourself rather than granting the agent `Write`. A read-only
auditor cannot edit the docs it is auditing, and that guarantee is worth more
than the convenience of it saving its own file.

## Parsers are plugins, and some of them are not in this repository

`StatementParser` declares the ids it answers to; `StatementParserRegistry` discovers
every implementation on the classpath and indexes it. Nothing lists parsers anywhere, so
the set can differ between checkouts and the ingest path never needs editing to add one.

- **In this repository:** `NorthwindStatementParser`, a demonstration parser for a bank
  that does not exist, reading a format this project invents. It is what makes `demo.cmd`
  work and what someone writing their own parser should copy.
- **Outside it:** set the `parsers.dir` property to a source tree laid out like this one
  and its `src/main/java` and `src/test/java` are compiled alongside. Set once in
  `~/.m2/settings.xml`, it applies to every build on that machine. Undefined, the
  `external-parsers` profile never activates.

Two consequences worth holding on to:

- **The test count differs by machine, legitimately.** With an external parser tree
  present you will see more tests than a bare clone does. Neither number is wrong.
- **Never put a filesystem path in the pom or in any tracked file.** That is what the
  property exists to prevent.

`printsControlTotals()` belongs to the parser, not to the ingest path. A parser whose
statements print no *independent* total returns false and needs a structural guard of its
own instead; one that returns true has its parse refused when the totals cannot be read.

## Demo mode

`demo.cmd` runs the app on generated sample statements with no data of anyone's own:
`application-demo.properties` redirects the database, redirects the backup directory
**and** disables backups, redirects the restore-preview directory, and turns AI off.

All four of those matter together, and each was configured independently of the datasource,
which is the whole reason the list keeps growing. Redirecting the datasource alone is not
enough, because the backup scheduler is configured independently — a demo left on the
default backup directory writes dumps of the demo database over the real daily backup,
under the same one-file-per-day name. Backups being *off* is not enough either: `backupNow()`
ignores the enabled flag, so "Back up now" then Restore → Preview builds a preview database,
and that path was a bare `${user.home}` interpolation with no property key until it was
given one — so no profile could move it, and a demo preview landed in `~/.pigpurchases`,
the live database's own folder.

If you add a setting that touches the filesystem or the network, ask whether demo mode
needs to redirect it too. This has now been missed twice; assume it will be missed again
unless asked deliberately.

`DemoStatements` computes each statement's control totals from its own rows rather than
printing constants, because `IngestService` refuses a statement whose rows disagree with
its printed totals. Hardcoding them would produce files the app rejects the first time
anyone tried the demo.

## Invariants to know before changing anything

- `AnalysisRun.month` is **not** a calendar month — it is the internal token
  `import-{id}`. Calendar grouping comes from each transaction's actual date.
- `TransactionMapping.countsAsSpend()` is the single authority on whether a row
  counts as spend. Ask it; never compare statuses directly.
- **Budgets are era-resolved** (`BudgetHistoryService`): each month is compared
  against the budget in force that month. An entry with no `BudgetAmountEra` rows
  resolves to its current amount for every month — that fallback is the
  compatibility guarantee for pre-era databases and restored old backups, so
  never "backfill" era rows for untouched entries.
- **Parked counts as spend.** Both flavours of excluded do not.
- Schema is `ddl-auto=update`, which only ever **adds**. New enum constants and
  new non-null columns are the known trap — see `EnumColumnMigration`.
- Most inter-table links are plain `Long` id fields with **no FK constraints**.
  Referential integrity is enforced in application code, not the database.

## Testing

Every test in this repository is portable: it builds its own PDFs and needs no personal
data. `*ValidationTest` classes reconcile a parser against real statements and live with
the parsers they validate, so an external parser tree brings its own.

They find statements through `LocalStatements`, which resolves a key from a system
property, an environment variable, or `statements.local.properties` in the project root
(gitignored). Three behaviours are deliberate and worth not "fixing":

- An **unset** key skips the test. That is CI and any fresh clone.
- A key that **is set but resolves to nothing** fails. Configuring a key is a statement of
  intent, and Surefire prints no reason for a skip, so a validation test that quietly
  stopped running would look exactly like one that passes.
- Keys describing one reference statement travel as a **group**: all set or all absent.

**A green CI run does not prove a parser still reconciles** — run the suite locally after
touching one. And treat an unexpected `Skipped:` count as a failure: `LocalStatements`
reads its config file relative to the process working directory, so running from the wrong
directory silently skips everything it configures.

Validation tests print statement detail only under `-Dpigpurchases.statements.verbose=true`.
Leave it off by default: those lines carry real filenames, dates and amounts, which is why
a review agent once declined to run the suite at all.
