# Pig Purchases — Budget Tracking

A desktop budget tracker for managing a monthly budget and analyzing spending.
You point it at folders of bank and credit-card statement PDFs; it parses them
into transactions, categorizes each one against your budget, and shows how actual
spending tracks to budget per month and on a rolling average.

Everything runs on your machine. The only thing that ever leaves it is a
transaction's *description and vendor text*, sent to the Claude API to categorize
the handful of transactions the deterministic rules can't place. **Dollar amounts
never leave the machine** — see [Privacy](#privacy).

> **Design documentation lives in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** —
> runtime topology, the layer map, UML and ER diagrams, and the reasoning behind
> the key invariants. This file covers what the app does, how to run it, and where
> it currently stands.

---

## How you use it

The UI is a single browser tab with nine screens, worked roughly left to right.

**Budget Entries** — define categories with a per-unit amount and a quantity
(e.g. "Pharm copays: 6 × $10"); the stored monthly budget is the total. Each entry
carries an optional free-text **hints** field, which is the knowledge base that
categorization gets better from. See [Writing hints](#writing-hints).

**Matching Hints** — one row per category, sortable by any header and by name to start
with, showing how many hints it has and how much they catch. Sorting by **Needs
attention** ranks by severity rather than alphabetically: a hint the app cannot honour
outranks one that is valid but has matched nothing yet, which outranks a category with
no hints at all. Click a category to see, add or remove its hints; terms are
quoted so the split points of a `+` hint are visible, and a hint's **matches N** badge
opens the transactions it actually catches (dated, with the amount signed the same way
the rest of the app signs it). Categories with **no** hints are listed too — those rely
on the category name or the AI. Hints still live on the budget entries; this is the only
view where hints on one category can be compared with hints on another.

It flags hints that are **ignored** (with the reason) separately from hints that simply
**match nothing yet**, previews what a new hint would catch *before* saving — including
whether it would take transactions from another category — and has a **conflict check**
that finds transactions claimed by more than one category, distinguishing a tie (which
parks the transaction) from an uneven overlap (where the longer hint silently wins).

**Statement Sources** — the named accounts, each with the folder its statements
live in. Add / edit / delete, with per-source spend exclusions shown inline.

**Ingest** — browse a source's folder, load a statement, and see the import
history as date chips. Clicking a date opens the PDF in Acrobat; 📁 reveals it in
its folder. Re-loading the same source + statement date **replaces** the prior
import rather than duplicating it. This screen is also where you add a **manual
transaction** — spend that never hits a statement (a Venmo balance, cash) — with a
same-day duplicate check.

**Mapping** — the categorization screen. One scrolling table, header pinned and
sortable, with a row per ingested statement: source, statement date, a 📄 icon that
opens the file in your default PDF app (the whole path is in its hover text),
transaction count, when it was last mapped, its mapped / parked / excluded counts,
and per-row **Review all**, **Re-map** and **Delete**. A statement nothing has mapped
yet shows "not mapped yet" rather than zeros — "0 parked" and "not mapped yet, so
unknown" are different facts — and sorts to the bottom of a count column whichever
way that column is sorted.
**Map Transactions** maps everything not
yet mapped; **Re-run mapping** re-does chosen statements. From here you also
review the parked "Other" bucket, review all transactions in a run with search,
review manual entries, and work through **potential duplicates** (same date + same
amount across statements), which you can dismiss permanently as "not duplicates".

**Spend: Monthly** — budget vs. actual for one calendar month: total, variance,
and a per-category breakdown. Click a category to list the transactions behind it
and reassign them in bulk. Each month can be flagged **complete**.

**Spend: Rolling** — the typical-month view: rolling average actual vs. budget
across the months you marked complete, per category and in total, plus a trend
chart and a per-category spend-over-time line chart.

**Settings** — annual budget (the monthly allowance is derived as ÷ 12), debug-log
retention, mapping-reminder day of month, database backup retention, back up now,
and restore.

**Debug** — a durable log of what the app did behind the scenes. Every ingest
(which file, which parser, whether it reconciled against the statement's own
totals, what a re-load carried over or lost), every mapping run (how many
transactions each pass placed, and how many were left parked), every manual
decision you made during review, and every outbound Claude API call with its
result or error. This is where you look when a total moved and you don't know why,
or when a mapping run categorizes nothing.

**Help is built in.** The sidebar **? Help** button opens a usage guide — an
overview plus a section per screen — and every screen's own **? Help** button opens
that same guide at its section, so the two can't drift apart.

### How a transaction gets categorized

Three passes, cheapest first, so the expensive one only sees what's left:

1. **Excluded transfers pass straight through.** Anything the source's parser rules
   flagged (a Crestline card payment made from Bayside) is recorded but never counted as
   spend and never categorized. This is what prevents double-counting.
2. **Deterministic matching** (`HintMatcher`) — free and repeatable. Matches the
   transaction against budget entry names and explicit `match:` hints.
3. **Remembered answers** (`merchant_categories`) — once a merchant has been
   categorized, by the AI or by you, that answer is reused on every later run with
   **no API call**. A merchant is paid for at most once, ever. A decision *you* made
   by hand also overrides a hint match from step 2 — otherwise excluding or
   re-categorizing a hint-matched charge would be undone by the next re-map. An AI
   answer does not: your own pattern outranks a guess.
4. **The Claude API** on whatever is left, then anything still unresolved is
   **parked**.

Parked transactions show as **"Other"** and **still count as spend** — real money
that simply hasn't been attributed yet, so a month's total is correct from the
moment mapping finishes. Correcting one during review teaches the merchant cache,
so it maps itself next time.

The AI pass is skipped entirely with no credentials configured, and any API failure
leaves those transactions parked rather than failing the run.

### The two kinds of "not spend"

Excluding something from spend comes in a standing flavour and a one-off flavour,
and the difference is whether a rule is created:

| | **🚫 Not spend — exclude** | **📌 Not spend — just this one** |
|---|---|---|
| Mapping status | `EXCLUDED` | `EXCLUDED_ONCE` |
| Creates a merchant rule | Yes — every future charge from that merchant is excluded too | **No** — nothing is remembered |
| Use for | Transfers, card payments, deposits | One-time exceptions: a trip paid out of gift money, a purchase you were reimbursed for |

Both are recorded against the run and neither counts toward spend, in the monthly
totals or the rolling average.

A one-off exclusion is attached to that exact transaction rather than to the
merchant, so — unlike every other manual decision, which is rebuilt from the
merchant cache — it has no rule to be restored from. Mapping therefore carries it
across a re-run explicitly, so re-mapping a statement can't silently turn a
deliberately-excluded charge back into spend. Reverse either kind by setting the
row back to a category or to "Other (parked)".

### Writing hints

Both sides are lowercased and stripped of everything that isn't a letter or digit
before comparing, because statement text carries punctuation the entry name doesn't:

```
"City Power" -> citypower   matches  "CITYPOWER 800-555-0142 CA"
"Novacell"    -> novacell    matches  "NOVA-CELL PCS SVC"
"Daily Grind"   -> dailygrind    matches  "DAILYGRIND*COFFEE"
```

The entry's **name** is always used as a pattern. Hints are otherwise prose written
for the AI pass to read, which can't be substring-matched — so a hint line declares
an explicit literal with a `match:` prefix, and **only those lines take part in the
deterministic pass**:

```
This is my auto loan payment
match: MERIDIAN MOTORS
match: CRESTLINE AUTO
```

A `match:` line can require several substrings at once by joining them with `+`;
all of them must appear. This pairs a short, ambiguous token with a distinctive
one, and tells apart two entries that share a prefix:

```
match: MP + MAILORDER    matches "MP RX00042 MAILORDER80 800-555-0175 CA"
match: HPK + monthly     matches only the HPK line that is also a monthly payment
```

Two rules keep this pass conservative, because a wrong automatic answer is worse
than parking a transaction for review:

- Patterns shorter than 4 normalized characters are ignored — the entry "Gas"
  would otherwise match "CITYPOWER" and "POWELL ST GARAGE". (A part inside a `+` composite
  may be shorter, since the AND is what makes it specific.)
- When several entries match, the longest pattern wins as the most specific
  ("Harbor Park Pass" over "Harbor Park"). If the longest is a tie between different
  entries, the transaction is parked rather than guessed at.

You don't have to write these by hand: when you categorize a parked transaction
during review, the app offers to save the matching hint for you.

---

## Running it

The Maven wrapper is committed, so no Maven install is needed — just a JDK 25 on
`PATH`. Use `.\mvnw.cmd` (PowerShell / cmd) or `./mvnw` (Git Bash).

```powershell
.\launch.cmd                      # or: .\mvnw.cmd -DskipTests spring-boot:run
.\mvnw.cmd clean package          # build
.\mvnw.cmd test                   # test
```

Wait for the log line `Started PigPurchasesApplication` (about 6 seconds), then
open <http://localhost:8080> — the server does not open it for you. Stop with
`Ctrl+C`, or `.\stop.cmd`.

Start it from your **own terminal**. That puts the server in your desktop session,
which is what makes "open in Acrobat" work. A server started from a service or a
different Windows session can't hand a file to your running Acrobat, and Acrobat
reports *"A running instance of Acrobat has caused an error."* Also use a real
browser, not VS Code's Simple Browser — its embedded viewer was the cause of an
earlier blank-PDF render that got misdiagnosed as a launch failure.

**After code changes**, click **Restart server** in the sidebar rather than killing
the terminal. It recompiles, then touches `target/classes/.reloadtrigger` so
DevTools fires exactly one restart; the page polls `/api/health` and refreshes
itself once the server is back.

### Enabling AI categorization

The Mapping screen states which mode a run will use, above the statement list — including
a warning when no key is found, since that is the usual reason a run categorizes nothing.

Set the key once for your user account, then restart the app:

```powershell
[Environment]::SetEnvironmentVariable('ANTHROPIC_API_KEY', 'sk-ant-...', 'User')
```

Open a new terminal afterwards so the variable is picked up (an `ant auth login`
profile works too — the SDK finds either). The Mapping screen shows which mode it's
in before you start, and the run summary reports how many transactions the AI
categorized versus how many came from remembered answers.

| Property | Default | Purpose |
|---|---|---|
| `pigpurchases.ai.enabled` | `true` | `false` skips the AI pass entirely |
| `pigpurchases.ai.model` | `claude-haiku-4-5` | Merchant → category is simple classification and Haiku is far cheaper; switch to a larger model if accuracy needs it |
| `pigpurchases.ai.batch-size` | `40` | Transactions per request |
| `pigpurchases.ai.max-tokens` | `16000` | Response cap — code default only; add it to `application.properties` to change it |

The first three are set in `application.properties`.

Because every merchant is charged for at most once, leaving the AI on is cheap
after the first month. Tests set `pigpurchases.ai.enabled=false`, so the suite
never calls the API even if your shell has a key exported.

**If a run categorizes nothing**, open the **Debug** screen — every outbound call
is logged there with its result or error (a rejected key, no credits), so you never
have to dig through the console.

Each transaction in a category drill-down also shows **why** it is there — nothing matched
it, you parked it by hand, it was remembered from an earlier decision. A row you parked by
hand is held there deliberately: no hint, remembered answer or AI pass will reclaim it, so
it stays in "Other" until you move it yourself.

### Testing

Tests come in two flavours, deliberately separated:

- **Portable tests** run anywhere, including CI. Parser tests build their own PDFs
  through the `TestPdfs` helper, and `IngestServiceIntegrationTest` drives a full
  ingest against an in-memory H2 (`application-test.properties`), covering parser
  dispatch, storage, exclusions, and idempotent re-ingest.
- **Validation tests** (`*ValidationTest`) reconcile each parser against the real
  statements on this machine — for Bayside, the sum of all signed transactions must
  equal ending minus beginning balance; for Crestline, the printed purchases and credits
  totals must match. Those PDFs are personal data and are never committed, so these
  tests `assumeTrue` the folder exists and **skip silently** elsewhere. A green CI
  run does not mean the parsers still reconcile — run the suite locally after
  touching a parser.

---

## Where your data lives

**The database is disposable; the PDFs are not.** Everything in the database can be
rebuilt by re-ingesting the statement folders.

**Live database**: `${user.home}/.pigpurchases/pig-purchases-db` (H2, file-based).
**Deliberately outside any cloud-synced folder.** An earlier build kept it in the
project directory under OneDrive, which synced the live file and once restored an
older version over it, losing a session's work. Never point the datasource at a
OneDrive / Dropbox / iCloud path. Schema is created and evolved by Hibernate
`ddl-auto=update`; there are no migration scripts. The one exception is
`EnumColumnMigration`, which runs at startup and converts the STRING-enum columns
from H2's native `ENUM(...)` type to `VARCHAR`, so a newly added enum constant can
be stored on a database created before it existed.

**Backups**: `${user.home}/pigpurchases-backups/` — outside OneDrive *and* outside
the live-db folder, so whatever can revert or corrupt the live db can't reach the
history. Each is a consistent SQL dump (H2 `SCRIPT TO`): portable, readable, and
independent of the live `.mv.db`.

- One file per calendar day, `pigpurchases-YYYY-MM-DD.sql`; a later backup the same
  day refreshes it. The newest N are kept (Settings → "Database backups to keep",
  default 30).
- Written on startup, every 10 minutes (`pigpurchases.backup.interval-ms`), on
  graceful shutdown, and on demand. Each run computes a cheap change signature and
  **skips writing when nothing changed**.
- **Anti-clobber guard**: if a snapshot shows real mappings dropping by more than
  half versus the last good backup — the "reverted to an empty state" failure — it
  is saved as `pigpurchases-YYYY-MM-DD-HHMMSS.SUSPECT.sql` with a warning in
  Settings **instead of overwriting** the good daily backup. `.SUSPECT` files are
  never auto-pruned.

### Restoring

**In the app (preferred)** — Settings → Database backups → **Restore…**

1. Pick a backup to **preview**. It loads into a *separate* preview database and
   every screen is routed at it (`SwitchableDataSource`), so you can browse the
   restored data. **Your live database is not touched.** A validation report (row
   counts, mapping distribution, referential-integrity checks, and a diff against
   current live data) appears in Settings, and a banner reminds you a preview is
   active.
2. Traverse the app and confirm the data looks right.
3. **Commit restore** (replaces the live db, after saving a `restore-rollback-*.sql`
   snapshot first) or **Cancel** (drops the preview; the live db was never
   modified). A crash or restart mid-preview is safe.

> Restores target backups from the current schema. Restoring a much older dump whose
> schema predates a column may need a manual migration.

**By hand**, when the app can't start:

1. Stop the app.
2. `restore.db.cmd "C:\Users\<you>\pigpurchases-backups\pigpurchases-YYYY-MM-DD.sql"`
   — stops anything on port 8080, keeps a safety copy of the current live db
   (`pig-purchases-db.pre-restore.mv.dbbak`), then loads the dump into a fresh live
   database.
3. `launch.cmd` and verify.

---

## Privacy

| Data | Location | Sent out? |
|---|---|---|
| Transaction amounts | Local database | ❌ Never |
| Spending history | Local database | ❌ Never |
| Budget definitions | Local database | ❌ Never |
| Account information | Local database | ❌ Never |
| Statement PDFs | Local disk | ❌ Never |
| Transaction descriptions | Local database | ✓ Only for categorization |
| Vendor names | Local database | ✓ Only for categorization |
| Account credentials | Not stored | ❌ Never |

Categorization requests carry only transaction descriptions, vendor names, and your
budget entry names and hints. Amounts, balances, dates, and account identifiers are
never included. The request body is built in a single method
(`AiCategorizationService.promptFor`) so the guarantee is checkable in one place,
and `AiCategorizationServiceTest` asserts it.

No cloud database, no analytics, no third-party sharing. **With no API key
configured, nothing at all leaves the machine** — the AI pass is skipped and
unresolved transactions stay in "Other".

---

## Current status

_Code-verified 2026-08-01. Runtime behavior against real statements was last
checked 2026-07-22._

### Working

- Budget entry CRUD with per-unit amount × quantity; annual budget with derived
  monthly allowance and a "remaining for open spend" readout
- Statement sources with add / edit / delete and per-source spend exclusions
- **PDF parsers** for three real formats: Crestline credit card, Bank of
  America deposit accounts (consumer + business), and Ridgeline Properties
  rent/utility statements — each covered by a reconciliation test against the
  control totals printed on the statement
- **Ingest**: in-app folder browser, parse-and-store, import history, idempotent
  re-ingest, and full traceability from any transaction back to its source file
  (view in a tab, open in Acrobat, reveal in folder)
- **Manual transactions** for spend that never hits a statement, with a same-day
  duplicate check
- **Duplicate detection** across statements, with permanent "not duplicates"
  dismissals
- **Mapping**: per-statement runs, the three-pass pipeline above, the parked
  "Other" bucket with manual assignment and bulk reassignment, hint-save prompts
  during review, re-running, and deletion
- **Merchant cache** so a merchant is only ever paid for once; manual corrections
  outrank AI answers, and deliberately un-categorizing something forgets the
  remembered answer
- **One-off exclusions** that keep a single transaction out of spend without
  teaching the app a merchant rule, and that survive a re-map
- **Built-in help**: a usage guide reachable from the sidebar and from a per-screen
  help button on every screen
- **Spend analysis**: monthly and rolling budget-vs-actual, per category and total,
  transaction-count columns, click-through to the transactions behind any figure,
  a trend chart, and a per-category spend-over-time chart. Months are grouped by
  each transaction's **actual date**, and only months flagged complete feed the
  rolling average
- **Data safety**: automated daily SQL backups with the anti-clobber guard, and
  preview-then-commit restore
- **Debug log** with configurable retention, and a mapping reminder on a chosen day
  of the month

### Known gaps

- **Parser rules are not editable in the UI.** Which parser runs comes from the
  source's `parserRules` JSON, but the source form only edits name and folder path.
  A source created through the UI therefore has no parser and ingest fails with
  `No parser configured for id: ''`. Set them via
  `PUT /api/statement-sources/{id}/parser-rules` until this is surfaced.
- **Spend semantics are type-based.** Spend nets by transaction type — purchases add,
  refunds subtract, and payments and deposits are money-in and never counted at all —
  and drops excluded transfers. Each *statement* is now reconciled against its own
  printed control totals at ingest and refused if it doesn't tie out, but nothing
  checks a whole month's computed spend against the statements that fed it.
- **Only PDF is supported.** CSV and OFX are not implemented.
- **No transaction-management screen.** Stored transactions can be recategorized
  from the Mapping and Spend screens, but there's no general view/edit surface.
- **No export.** Reports can't be downloaded.
- **Dead code**: `MappingService.createRun` / `map(runId)` are the pre-per-file
  month-run path. No controller reaches them; only the tests do.

### Planned

1. Surface parser selection and exclusions in the statement-source UI, so a source
   created in the app can actually be ingested.
2. Transaction management screen: view / edit / recategorize.
3. Export and reporting.
4. Additional statement formats (CSV, OFX) as needed.

---

## API reference

Served by nine controllers on `localhost:8080`.

**Budget entries** — `GET|POST /api/entries`, `PUT|DELETE /api/entries/{id}`,
`POST /api/entries/{id}/hints` (append a `match:` line, idempotent),
`DELETE /api/entries/{id}/hints` `{hint}` (remove one line, leaving the entry's other
rules and its prose untouched)

**Hints** — `GET /api/hints` (every rule, with why it's ignored and how many
transactions it matches, plus every category sorted by name), `GET /api/hints/conflicts`
(transactions claimed by more than one category, flagged for whether the tie parks them),
`POST /api/hints/preview` `{entryId, hint}` (what a rule would catch, and what it would
take from elsewhere — `samples` are `{transactionId, date, description, amount}` and are
capped at 25 while `matchCount` stays exact)

**Settings** — `GET|PUT /api/settings` (annual budget, debug-log retention,
notification day, backup retention)

**Statement sources** — `GET|POST /api/statement-sources`,
`PUT|DELETE /api/statement-sources/{id}`,
`GET|PUT /api/statement-sources/{id}/parser-rules` *(not surfaced in the UI — see
Known gaps)*

**Ingest** — `GET /api/statement-sources/{id}/files?relPath=` (browse; paths that
escape the source folder are rejected), `POST /api/statement-sources/{id}/ingest`,
`GET /api/statement-sources/{id}/imports`, `GET /api/imports/{id}/file`
(range-capable, renders in a tab), `POST /api/imports/{id}/open`,
`POST /api/imports/{id}/reveal`

**Transactions** — `GET /api/transactions?sourceId=&month=`,
`GET /api/transactions/{id}/source`, `POST /api/transactions/manual`,
`GET /api/transactions/manual/run`, `DELETE /api/transactions/{id}`,
`GET /api/transactions/duplicates`, `POST /api/transactions/duplicates/dismiss`

**Mapping** — `GET /api/mapping/files` (every statement with its mapped state,
counts, and `fullPath` for the file link, plus `aiAvailable`),
`POST /api/mapping/map-unmapped`,
`POST /api/mapping/remap` `{importIds}`, `POST /api/mapping/migrate` (one-time,
idempotent: splits legacy month-runs into per-file runs),
`GET /api/analysis-runs/{id}/mappings?status=PARKED`,
`PUT /api/analysis-runs/{id}/mappings/{transactionId}` (assign; null
`budgetEntryId` parks it, `exclude:true` marks it not-spend and remembers the
merchant, `exclude:true, once:true` excludes only that transaction and creates no
rule),
`DELETE /api/analysis-runs/{id}`

**Analysis** — `GET /api/analysis/months` (calendar months with data, plus
completeness and unmapped counts), `PUT /api/analysis/months/{month}/complete`,
`GET /api/analysis/month/{month}`,
`GET /api/analysis/month/{month}/transactions?category=` (an entry id, `other`, or
`__excluded__`), `GET /api/analysis/rolling`, `GET /api/analysis/trends`,
`GET /api/analysis/category-trend?category=`

**Backup / restore** — `GET /api/backup/status`, `POST /api/backup/now`,
`GET /api/restore/status`, `POST /api/restore/preview` `{file}`,
`POST /api/restore/commit`, `POST /api/restore/cancel`, `POST /api/restore/comment`

**Debug log** — `GET /api/debug-log` (newest first), `DELETE /api/debug-log`. Every
ingest records its outcome here — reconciled, unreconcilable, or refused — so silence
means the ingest never ran rather than that it was fine.

**Housekeeping** — `GET /api/health` (liveness, used by the restart flow),
`POST /api/restart` (recompile + DevTools reload),
`GET /api/notification/status` (is a mapping reminder due?)

---

## Project layout

```
PigPurchases/
├── src/main/java/com/pigpurchases/
│   ├── PigPurchasesApplication.java   Spring Boot entry (root package, so
│   │                                  component/entity/repository scan works)
│   ├── config/     SwitchableDataSource + DataSourceConfig (restore preview),
│   │               EnumColumnMigration (ENUM → VARCHAR at startup)
│   ├── model/      JPA entities: BudgetEntry, Transaction, StatementSource,
│   │               StatementImport, AnalysisRun, AnalysisRunSource,
│   │               TransactionMapping, MerchantCategory, MonthStatus,
│   │               DismissedDuplicate, AppSettings, AppLogEntry
│   ├── parser/     StatementParser (interface) + Crestline / Bayside / Ridgeline,
│   │               ParsedStatement, ParsedTransaction, ExclusionRule
│   ├── repository/ one Spring Data repo per entity
│   ├── service/    IngestService, MappingService, HintMatcher,
│   │               AiCategorizationService, AnalysisService, ManualEntryService,
│   │               BackupService, RestoreService, DebugLogService, BudgetService
│   └── server/     BudgetController, MappingController, AnalysisController,
│                   ManualEntryController, BackupController, RestoreController,
│                   DebugLogController, NotificationController, DataInitializer
├── src/main/resources/
│   ├── static/index.html          the frontend: markup, styles, and all DOM/fetch code
│   ├── static/app-math.js         its pure functions (money, dates, escaping), split out
│   │                              so AppMathTest can cover them. Plain <script>, no bundler
│   └── application.properties     H2, backups, DevTools, AI settings
├── src/test/java/com/pigpurchases/
│   ├── TestPdfs.java              generates PDFs so tests need no real statements
│   ├── config/                    EnumColumnMigration (ENUM → VARCHAR) tests
│   ├── web/                       AppMathTest — runs static/app-math.js and checks its
│   │                              signedSpend still agrees with the server's
│   ├── parser/                    portable parser tests + *ValidationTest
│   ├── service/                   mapping, analysis, ingest, AI, manual entry
│   └── server/                    controller tests
├── docs/ARCHITECTURE.md           design docs: diagrams, invariants, rationale
├── .github/workflows/ci.yml       mvnw test on Temurin 25
├── launch.cmd / stop.cmd / restart.cmd / restore.db.cmd
└── mvnw, mvnw.cmd, pom.xml
```

> A Python `.venv` plus `import_budget.py` and `create_icon.py` are one-off helper
> scripts (spreadsheet import, icon generation), not part of the running app.
> `budget-import.json` and the data/db files hold personal amounts and are
> git-ignored.
