# Pig Purchases - Budget Tracking Application

## Overview

Pig Purchases is a desktop budget tracking application for managing monthly budgets and analyzing spending patterns. All monetary data stays local on your PC—no cloud processing of transaction amounts or sensitive financial data.

## Core Requirements

### 1. Budget Management
- **Budget Entry Configuration**: Define budget categories with monthly allowance amounts
- **Quantity Support**: Budget items can have quantities (e.g., "Pharm copays: qty 6 × $10 = $60/month")
- **Budget Hints**: Optional hints field to assist with automatic transaction categorization
- **One-Time Import**: Ability to bulk-import budget entries from spreadsheets

### 2. Monthly Statement Processing & Transaction Tracking
- **Statement File Uploads**: Import credit card and bank statements (CSV, PDF, OFX formats)
- **Statement Source Management**: Maintain a list of file types and account names to track which statements have been imported
- **Automatic Categorization**: Parse transaction descriptions and vendors from statements, then auto-categorize against defined budget entries
- **Privacy-First Categorization**: Send only transaction descriptions and vendor names to AI for categorization—all dollar amounts remain local
- **Transaction Storage**: Store hundreds of transactions per month with:
  - Transaction date
  - Description
  - Vendor name
  - Amount (stored locally, never sent out for categorization)
  - Categorization (which budget entry it maps to)
  - Month (YYYY-MM format for grouping)
  - User notes/edits
- **Category Refinement**: Iteratively improve categorization by refining budget entry hints based on user feedback

### 3. Monthly Mapping Runs
- **User-Asserted Months**: An analysis month is defined by explicitly choosing one
  ingested statement per source. Nothing is inferred from dates — a statement dated
  July can be the right statement for the June run
- **Complete Coverage**: Every statement source must contribute a statement to a run
- **No Double-Counting**: Statements consumed by a run aren't offered to later runs
  unless explicitly requested
- **Hint-Driven Categorization**: Map transactions to budget entries using entry hints
  first, AI only for the remainder
- **Parked Items**: Anything uncategorized is grouped as "Other" and still counted as
  spend, pending a review session that produces new hints
- See [Transaction Mapping — Analysis Runs](#transaction-mapping--analysis-runs)

### 4. Spend Analysis & Reporting
- **Summary Reporting**: Total budget vs. total actual spend for the month, with variance and percent used
- **Rolling Averages**: Track rolling average budget and spend across months
- **Per-Item Breakdown**: View spend by budget entry category
- **Monthly Comparison**: Compare spend trends across months
- **Graph/Chart Visualization**: Display spending patterns visually
- **Export Functionality**: Download reports in standard formats

### 5. UI/UX
- **Budget Entries Tab**: Table view with add/edit/delete operations
- **Statement Sources Tab**: Manage the accounts and their statement folders
- **Ingest Tab**: Browse a source's folder and load statements; open them in Acrobat
- **Mapping Tab**: "Map Transactions" button plus the status of every previous run
- **Analysis Tab**: View spend calculations and reports
- **Modal Dialogs**: Inline add/edit forms for budget entries and transactions
- **Edit Buttons**: Per-row edit buttons to modify transaction details and categorization

## Technical Architecture

### Technology Stack
- **Backend**: Spring Boot 3.5.16 (Java 25)
- **Database**: H2 (embedded, file-based, no separate server needed)
- **Frontend**: Vanilla JavaScript + HTML/CSS (not a heavy framework)
- **ORM**: Spring Data JPA with Hibernate
- **PDF text extraction**: Apache PDFBox 3.0.3
- **Build**: Maven via the bundled wrapper (`mvnw`)

### Data Storage Strategy

#### Database (H2)
- **Location**: `pig-purchases-db` (file-based, stored locally)
- **Auto-initialization**: Database schema created automatically on first run
- **Data Migration**: Existing flat-file budget data migrated to database on startup

#### Database Schema

**budget_entries** table
```sql
id (Long, auto-increment)
name (String)
monthly_allowance (BigDecimal)
quantity (Integer, default 1)
hints (String, up to 1024 chars)
```

**transactions** table
```sql
id (Long, auto-increment)
transaction_date (LocalDate)
description (String)
vendor (String)
amount (BigDecimal, signed as the statement prints it)
txn_month (String, YYYY-MM; "month" is reserved in H2)
statement_import_id (Long)   -- the ingest batch this row came from
statement_source_id (Long)   -- denormalized for convenient querying
type (String)                -- PURCHASE, PAYMENT, CREDIT, DEPOSIT, WITHDRAWAL, ...
exclude_from_spend (boolean) -- transfers etc.: kept, but not counted as spend
budget_entry_id (Long, foreign key to budget_entries)  -- not populated yet
notes (String, up to 1024 chars)
```

**statement_sources** table
```sql
id (Long, auto-increment)
name (String)
folder_path (String, absolute path to this account's statement folder)
parser_rules (CLOB, JSON: which parser to use + spend exclusions)
```

**statement_imports** table
```sql
id (Long, auto-increment)
statement_source_id (Long)
statement_date (LocalDate)
file_name (String)
relative_path (String, path under the source folder)
imported_at (LocalDateTime)
transaction_count (int)
```

**app_settings** table — a single row (id=1) holding `annual_budget`.

Schema is created and evolved by Hibernate `ddl-auto=update`; there are no
migration scripts.

### API Endpoints

All live endpoints are served by `BudgetController` under `/api`.

#### Budget Management
- `GET /api/entries` — List all budget entries
- `POST /api/entries` — Create new budget entry
- `PUT /api/entries/{id}` — Update budget entry
- `DELETE /api/entries/{id}` — Delete budget entry

#### Settings
- `GET /api/settings` — Annual budget + calculated monthly allowance
- `PUT /api/settings` — Set the annual budget

#### Statement Sources
- `GET /api/statement-sources` — List sources (with their spend exclusions)
- `POST /api/statement-sources` — Create a source
- `PUT /api/statement-sources/{id}` — Update name / folder path (parser rules preserved)
- `DELETE /api/statement-sources/{id}` — Delete a source
- `GET|PUT /api/statement-sources/{id}/parser-rules` — Read/write the parser-rules
  JSON. **Not surfaced in the UI** — see the note under Ingest below.

#### Ingest
- `GET /api/statement-sources/{id}/files?relPath=` — Browse folders/PDFs under the
  source folder (paths that escape the folder are rejected)
- `POST /api/statement-sources/{id}/ingest` — Parse a PDF and store its transactions
- `GET /api/statement-sources/{id}/imports` — Import history for a source
- `GET /api/imports/{id}/file` — Stream the original PDF (range-request capable, so
  it renders in a browser tab)
- `POST /api/imports/{id}/open` — Open the PDF in the desktop PDF app (Acrobat)
- `POST /api/imports/{id}/reveal` — Show the PDF in its folder

#### Transactions
- `GET /api/transactions?sourceId=&month=` — List stored transactions
- `GET /api/transactions/{id}/source` — Trace a transaction to its import + source file

#### Analysis
- `POST /api/summary` — **Placeholder.** Regex-sums pasted text; does not read the
  stored transactions. See Current Implementation Status.

#### Housekeeping
- `GET /api/health` — Liveness check used by the restart flow
- `POST /api/restart` — Restart via DevTools to pick up recompiled classes

### Privacy & Security

**What Stays Local**
- All transaction amounts (never sent to any service)
- All spending history
- All budget definitions
- All account information

**What Gets Sent Out (Optional)**
- Only when user initiates categorization:
  - Transaction description (e.g., "Fresh Market")
  - Vendor name
  - Available budget entry hints
- Sent to the Claude API for categorization only; nothing is stored there

**No External Dependencies**
- No cloud database
- No analytics tracking
- No data sharing with third parties

> Status: the boundary above is enforced in code and asserted by a test. With no
> API key configured, **nothing at all leaves the machine** — the AI pass is
> skipped and unresolved transactions stay in "Other".

## Current Implementation Status

_Last verified: 2026-07-22 (end-to-end against a running server, real statements)._

### ✅ Completed & verified working
- Spring Boot + Maven scaffolding; embedded Tomcat; H2 as the single source of truth
- JPA entities and repositories for budget entries, transactions, statement
  sources, statement imports, and app settings
- Budget entry CRUD end-to-end (add / edit / delete), with per-unit currency and
  quantity fields; the stored `monthlyBudget` is the **total** (per-unit × quantity)
- Annual budget setting, with monthly allowance derived as annual ÷ 12, and a
  "remaining for open spend" readout
- One-time migration from the legacy flat file (`pig-purchases-data.txt`) on first startup
- **Statement sources**: named account folders with add / edit / delete, plus
  per-source spend exclusions surfaced in the UI
- **PDF statement parsers** for three real formats — Crestline credit card,
  Bayside deposit accounts (consumer + business), and Ridgeline
  Properties rent/utility statements
- **Ingest**: in-app folder browser, parse-and-store, import history, and
  idempotent re-ingest (re-loading the same source + statement date replaces the
  prior import rather than duplicating it)
- **Traceability**: every stored transaction points back to its import and source
  file; a loaded statement can be viewed in a browser tab, opened in the desktop
  PDF app (Acrobat), or revealed in its folder
- **Reconciliation tests** that verify each parser's output against the control
  totals printed on the statement (see Development & Testing)
- **Mapping runs**: the Mapping screen, run setup with consumed-statement
  hiding, deterministic categorization, the parked "Other" bucket with manual
  assignment, re-running, and deletion — everything in
  [Transaction Mapping — Analysis Runs](#transaction-mapping--analysis-runs),
  including the Claude-API categorization pass for what the matcher can't resolve

### 🧱 Known gaps in what's built
- **Parser rules are not editable in the UI.** Which parser runs is decided by the
  source's `parserRules` JSON, but the source form only edits name and folder path.
  A source created through the UI therefore has no parser, and ingest fails with
  `No parser configured for id: ''`. Rules must be set via
  `PUT /api/statement-sources/{id}/parser-rules` until this is surfaced.
- **The AI pass has not been run against real data yet.** It is implemented and
  unit-tested, but no Anthropic credentials are configured on this machine, so
  every run so far has fallen back to the deterministic matcher alone. On the
  June 2026 run (181 transactions across all four sources) that matcher mapped 9,
  excluded 3 transfers, and parked 169 — every match correct, but recall is low
  because 29 of 32 budget entries have no hints and merchant names like
  `FRESHMARKET WHSE` don't resemble entry names like `Groceries`. Set
  `ANTHROPIC_API_KEY` (below) and re-run the mapping to close that gap.
- **Analyze Spend is still the original placeholder.** It posts pasted textarea
  lines to `POST /api/summary`, which regex-sums any line containing `$`, and
  ignores the parsed transactions sitting in H2. The rolling average is likewise
  synthetic — it averages a one-element history built from the current month.
- Only PDF is supported. CSV and OFX are not implemented.

### ⚠️ Planned
- **Real analysis**: total spend vs. total budget per month driven by mapping runs;
  per-entry breakdown, month-over-month comparison, rolling averages, charts
- **Parked-transaction review**: work through the "Other" bucket and turn the
  results into budget entry hints
- **Transaction Management UI**: view, edit, and recategorize stored transactions
- **Parser rules UI**: pick a parser and edit exclusions when creating a source
- **Export Functionality**: generate and download reports

## Transaction Mapping — Analysis Runs

_Implemented, apart from the AI pass — see "How mapping decides" below._

Analysis is **monthly**, and a month is defined by an explicit **mapping run**: a
named month bound to exactly one ingested statement per statement source.

Four principles govern the whole exercise:

1. **Privacy-First**: statements are parsed server-side; only descriptions and
   vendor names ever leave the machine, and only when hint matching has failed
2. **Amounts Never Leave**: dollar amounts, balances, and account details are
   never included in a categorization request
3. **Iterative Learning**: the budget entries' `hints` field is the knowledge base,
   and it is what gets better over time
4. **User Feedback Loop**: corrections made during review become new hints, so the
   same transaction maps itself next month

### Why runs exist

Statement periods don't line up with calendar months. A Crestline statement closing
06/11 covers roughly 05/12–06/11, so its transactions carry May *and* June dates.
Rather than infer a month from transaction dates, **the user asserts which
statements constitute a month** — that knowledge lives with the person, not the
data.

Consequently the run's month label is **authoritative**, and it is the *only*
thing that assigns a transaction to a month. None of the dates already in the
data are used for that purpose — not a transaction's own `transaction_date`, not
the `txn_month` recorded at ingest, and **not the statement's own statement
date**.

### Setting up a run

On the **Mapping** screen, **Map Transactions** prompts for:

1. The month the run represents (YYYY-MM).
2. Exactly one ingested statement per statement source.

**A statement's date has nothing to do with which run it belongs to.** Billing
cycles differ per account, and the user knows how each one maps to a month. For
example, the Ridgeline statement *dated July* carries June's utility charges,
so it is the statement selected for the **June** run. This is exactly why the
statements are chosen by hand rather than matched automatically.

It follows that the per-source picker must offer every ingested statement for that
source, in date order, and must never filter by the run's month.

**Every source must contribute a statement.** A run cannot be created until all
of them have exactly one selected, which guarantees each month's totals are
complete and comparable month over month.

### Consumed statements

A statement used by a mapping run is **consumed**, and is not offered again when
setting up a later run. Since statement dates carry no meaning here, this is the
safeguard that stops the same Crestline statement being counted into both the May and
the June run — an error that would otherwise be silent and would corrupt both
months.

- Consumed statements are **hidden from the picker by default**.
- They can be brought back with an explicit *"show already-used statements"*
  toggle, which marks each one with the run that consumed it. Choosing one is
  allowed but deliberate.
- The statement already selected by the run being edited is always shown — it is
  consumed by that run, not by another.
- Deleting a run releases its statements back to the pool. Re-running a run's
  mapping does not change what it consumes.

Consumption is **derived** from the run/statement links rather than stored as a
flag on the statement, so it cannot drift out of step with the runs themselves.

### How mapping decides

Every transaction in the selected statements is carried into the run, then
resolved in this order:

1. **Excluded transfers pass through untouched.** Transactions already flagged
   `excludeFromSpend` by their source's parser rules (Crestline card payments made
   from Bayside, Ridgeline rent covered by insurance) are recorded against the run
   but never counted as spend and never categorized — that is what stops
   double-counting.
2. **Deterministic matching runs first** (`HintMatcher`, implemented). Each
   remaining transaction is matched against the budget entries. This is cheap,
   repeatable, and keeps the entries the real knowledge base.
3. **Only the leftovers go to the Claude API** (`AiCategorizationService`).
   Descriptions and vendor names that matching could not resolve are sent for
   categorization, along with the budget entry names and their hints. **Amounts,
   balances, dates, and account identifiers are never included** — the request is
   built in one method (`promptFor`) so that guarantee is checkable in one place,
   and `AiCategorizationServiceTest` asserts it.

   Repeated merchants are collapsed first: five Fresh Market visits become one line in
   the request and all five resolve identically. The model is told to answer
   `null` rather than guess when nothing fits or two categories fit equally well,
   so a wrong answer is preferred over a review item only when the model is
   confident.
4. **Anything still unresolved is parked** — see below.

The AI pass is skipped entirely when no credentials are configured, and any API
failure leaves those transactions parked rather than failing the run. Rejected
credentials switch it off for the rest of the session instead of re-failing once
per batch.

#### Remembered answers (the cost control)

Between hint matching and the AI pass sits a **merchant cache**
(`merchant_categories`). Once a merchant has been categorized — by the AI, or by
you correcting it during review — the answer is stored keyed by the normalized
merchant text, and every later run reuses it **without another API call**. So a
given merchant is paid for at most once, ever; re-running a month after tweaking
hints costs only whatever genuinely new merchants appeared. The run summary
breaks this out: *"12 newly by AI, 137 remembered, no API call"*.

The cache is a feedback loop, not just a ledger:

- A **manual correction always wins** over a remembered AI answer, and is itself
  remembered — fix "FRESHMARKET" once and every future Fresh Market line maps itself.
- Deliberately **un-categorizing** a transaction (parking it by hand) forgets the
  remembered answer, so a wrong guess doesn't keep coming back.
- A remembered answer whose budget entry has since been **deleted** is purged on
  the next run rather than applied.

#### Model choice

The default is **Haiku 4.5** (`pigpurchases.ai.model`), because merchant →
category is a simple classification and Haiku is roughly 5× cheaper than Opus.
Switch to `claude-opus-4-8` if accuracy on your statements needs it. Because of
the cache, the model cost is a one-time charge per merchant regardless.

Hints live on **budget entries**, not on statement sources. The source a
transaction came from is supplied to the mapper as context, but the knowledge base
being refined over time is the per-entry hints.

#### Writing hints that the matcher can use

Both sides are lowercased and stripped of everything that isn't a letter or digit
before comparing, because statement text carries punctuation the budget entry name
doesn't:

```
"City Power" -> citypower   matches  "CITYPOWER 800-555-0142 CA"
"Novacell"    -> novacell    matches  "NOVA-CELL PCS SVC"
"Daily Grind"   -> dailygrind    matches  "DAILYGRIND*COFFEE"
```

The entry's **name** is always used as a pattern. Hints are otherwise prose
written for the AI pass to read, which cannot be substring-matched — so a hint
line may declare an explicit literal with a `match:` prefix, and **only those
lines take part in the deterministic pass**:

```
This is my auto loan payment
match: MERIDIAN MOTORS
match: CRESTLINE AUTO
```

Two rules keep this pass conservative, because a wrong automatic answer is worse
than parking a transaction for review:

- Patterns shorter than 4 normalized characters are ignored — the entry "Gas"
  would otherwise match "CITYPOWER" and "POWELL ST GARAGE".
- When several entries match, the longest pattern wins as the most specific
  ("Harbor Park Pass" over "Harbor Park"). If the longest is a tie between different
  entries, the transaction is parked rather than guessed at.

### Parked transactions ("Other")

A run that finishes with unmapped transactions is still **complete and usable** —
analysis reports on it immediately rather than blocking.

- Parked transactions are grouped under **"Other"**.
- **Their cost still counts toward the month's total actual spend.** "Other" is
  real money that has simply not been attributed yet, so it appears as actual
  spend with no budgeted counterpart. A month's total is therefore correct from
  the moment the run completes, even before every line is attributed.
- The Mapping screen surfaces the parked count (e.g. *"June 2026 — 517 mapped, 12
  parked"*) and prompts a review session, where those transactions are identified
  together and the resulting hints are added to the relevant budget entries.
- Re-running the month's mapping afterwards reclassifies them out of "Other".

### Re-running

Re-running mapping for a month **replaces that month's prior mapping results**,
mirroring idempotent ingest. Ingested transactions themselves are never touched —
mapping is a separate layer over them, so a re-run is always safe.

### The Mapping screen

Lists every run and its status: the month, which statement was chosen for each
source, when it was mapped, and the mapped-vs-parked counts. This is also where
the review prompt for parked transactions lives.

### What analysis then shows

Because every transaction in a month is attributed to a run, the analysis screen
can report on a month directly:

- **Total actual spend vs. total budget for the month**, with variance — including
  parked spend under "Other"
- Per-budget-entry breakdown of actual vs. allowance
- Month-over-month comparison and rolling averages across completed runs

### Data model

Mapping results are kept **out of** the `transactions` table so re-running is
clean and prior runs stay intact:

```sql
analysis_runs          id, run_month (YYYY-MM, unique), created_at, mapped_at,
                       status (DRAFT | MAPPED), mapped_count, parked_count, excluded_count
analysis_run_sources   id, analysis_run_id, statement_source_id, statement_import_id
                       unique (analysis_run_id, statement_source_id)
transaction_mappings   id, analysis_run_id, transaction_id,
                       budget_entry_id (null => PARKED or EXCLUDED),
                       status (MAPPED_HINT | MAPPED_AI | MAPPED_MANUAL | PARKED | EXCLUDED),
                       reason
                       unique (analysis_run_id, transaction_id)
merchant_categories    id, merchant_key (unique), budget_entry_id,
                       source (AI | MANUAL), sample_description, reason, updated_at
```

`merchant_categories` is the cross-run merchant cache: it is what stops the AI
pass re-charging for a merchant it (or you) already categorized. It is keyed by
normalized merchant text, not by run, because a merchant maps to the same
category regardless of month.

`status` distinguishes PARKED (real spend, not yet attributed) from EXCLUDED
(a transfer that must never count) — both have a null `budget_entry_id`, so the
distinction cannot be inferred from that column alone.

Statement reuse across runs is deliberately **not** a database constraint: it is
hidden by default but permitted when explicitly requested, so it is enforced in
the service, not the schema.

The unused `transactions.budget_entry_id` column is superseded by
`transaction_mappings` and can be dropped.

### Endpoints

- `GET /api/mapping/setup?includeConsumed=` — everything the dialog needs: each
  source with its selectable statements. Consumed ones are omitted unless asked
  for, in which case each carries the month that consumed it.
- `GET /api/analysis-runs` — every run with its statements, status and counts
- `POST /api/analysis-runs` — create a run (month + one import per source; rejects
  an incomplete source set, a duplicate month, or a consumed statement unless
  `allowConsumed` is set)
- `POST /api/analysis-runs/{id}/map` — execute or re-execute mapping
- `GET /api/analysis-runs/{id}/mappings?status=PARKED` — the "Other" bucket, for
  review sessions
- `PUT /api/analysis-runs/{id}/mappings/{transactionId}` — manual categorization;
  a null `budgetEntryId` parks it again
- `DELETE /api/analysis-runs/{id}` — delete a run, releasing its statements

## Data Privacy Summary

| Data Type | Location | Sent Out? |
|-----------|----------|-----------|
| Budget entries | Local database | ❌ No |
| Transaction amounts | Local database | ❌ No |
| Transaction descriptions | Local database | ✓ Only for categorization |
| Vendor names | Local database | ✓ Only for categorization |
| Spending history | Local database | ❌ No |
| Account credentials | Not stored | ❌ No |

## Development & Testing

The Maven wrapper is committed, so no Maven install is needed — just a JDK 25 on
`PATH`. Use `./mvnw` (Git Bash) or `.\mvnw.cmd` (PowerShell / cmd).

### Run
```powershell
.\launch.cmd
```
or equivalently `.\mvnw.cmd -DskipTests spring-boot:run`.

Wait for the log line `Started PigPurchasesApplication` (about 6 seconds), then
open <http://localhost:8080> in your browser — the server does not open it for
you. Stop with `Ctrl+C`.

Start it from your **own terminal**: that puts the server in your desktop
session, which is what makes "open in Acrobat" work (see below).

### Build
```powershell
.\mvnw.cmd clean package
```

### Test
```powershell
.\mvnw.cmd test
```

### Enabling AI categorization

The AI pass needs Anthropic credentials. Set the key once, for your user account,
then restart the app:

```powershell
[Environment]::SetEnvironmentVariable('ANTHROPIC_API_KEY', 'sk-ant-...', 'User')
```

Open a new terminal afterwards so the variable is picked up (an `ant auth login`
profile works too — the SDK finds either). The Mapping dialog says which mode it
is in before you start a run, and the run summary reports how many transactions
the AI categorized.

Relevant settings in `application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `pigpurchases.ai.enabled` | `true` | Set `false` to skip the AI pass entirely |
| `pigpurchases.ai.model` | `claude-haiku-4-5` | Model used for categorization (Opus 4.8 for higher accuracy) |
| `pigpurchases.ai.batch-size` | `40` | Transactions per request |

Every merchant is charged for at most once — answers are cached across runs (see
Remembered answers above), so leaving the AI on is cheap after the first month.

Tests set `pigpurchases.ai.enabled=false`, so the suite never calls the API even
if your shell has a key exported.

### Restarting after code changes
Click **Restart server** in the sidebar. Spring Boot DevTools reloads the
recompiled classes; the page polls `/api/health` and refreshes itself once the
server is back. This is preferable to killing and relaunching the terminal.

### Opening statements in Acrobat

Loaded statements appear on the Ingest screen as date chips. Clicking the date
hands the PDF to the desktop default handler (Acrobat) via
`POST /api/imports/{id}/open`; the 📁 button next to it reveals the file in its
folder. If the desktop launch fails, the UI silently falls back to opening the
PDF in a browser tab.

Two things matter for this to work:

- **The server must run in your own interactive desktop session** — i.e. you
  started it from your terminal, as above. A server started from a service, a
  different Windows session, or at a different integrity level cannot hand the
  file to your already-running Acrobat, and Acrobat reports *"A running instance
  of Acrobat has caused an error."*
- **Use a real browser, not VS Code's Simple Browser.** The embedded viewer was
  the cause of an earlier blank-PDF render that was misdiagnosed as a launch
  failure.

### Test layout

Tests come in two flavours, deliberately separated:

- **Portable tests** run anywhere, including CI. The parser tests build their own
  PDFs through the `TestPdfs` helper, and `IngestServiceIntegrationTest` drives a
  full ingest against an in-memory H2 (`application-test.properties`), covering
  parser dispatch, storage, exclusions, and idempotent re-ingest.
- **Validation tests** (`*ValidationTest`) reconcile each parser against the real
  statements on this machine — for Bayside, the sum of all signed transactions must
  equal ending minus beginning balance; for Crestline, the printed purchases and
  credits totals must match. Those PDFs are personal data and are never
  committed, so these tests `assumeTrue` the folder exists and **skip silently**
  elsewhere. A green CI run does not mean the parsers still reconcile; run the
  suite locally after touching a parser.

## File Structure
```
PigPurchases/
├── src/main/java/com/pigpurchases/
│   ├── PigPurchasesApplication.java (Spring Boot entry — root package so
│   │                                 component/entity/repository scan works)
│   ├── model/
│   │   ├── BudgetEntry.java, Transaction.java, AppSettings.java
│   │   ├── StatementSource.java  (account folder + parser rules)
│   │   ├── StatementImport.java  (one ingested statement file)
│   │   ├── MerchantCategory.java (cross-run merchant→category cache)
│   │   ├── AnalysisRun.java      (a month + its chosen statements)
│   │   ├── AnalysisRunSource.java(one source's statement in a run)
│   │   └── TransactionMapping.java (how one txn resolved in one run)
│   ├── parser/
│   │   ├── StatementParser.java  (interface: Path -> ParsedStatement)
│   │   ├── CardStatementParser.java, DepositStatementParser.java,
│   │   │   PropertyStatementParser.java
│   │   ├── ParsedStatement.java  (transactions + printed control totals)
│   │   ├── ParsedTransaction.java, ExclusionRule.java
│   ├── repository/               (one Spring Data repo per entity)
│   ├── service/
│   │   ├── IngestService.java    (parse -> exclude -> store, idempotent)
│   │   ├── MappingService.java   (run setup, validation, mapping execution)
│   │   ├── HintMatcher.java      (pure matching logic, heavily unit-tested)
│   │   ├── AiCategorizationService.java (Claude API pass; the privacy boundary)
│   │   ├── BudgetService.java    (pure calculation logic, @Service bean)
│   │   └── MonthlyHistoryEntry.java
│   └── server/
│       ├── BudgetController.java  (entries, settings, sources, ingest)
│       ├── MappingController.java (mapping runs and review)
│       └── DataInitializer.java   (one-time flat-file → DB migration)
├── src/main/resources/
│   ├── static/index.html         (the entire vanilla-JS frontend)
│   └── application.properties    (H2 config)
├── src/test/java/com/pigpurchases/
│   ├── TestPdfs.java             (generates PDFs so tests need no real statements)
│   ├── parser/                   (portable parser tests + *ValidationTest)
│   ├── service/IngestServiceIntegrationTest.java
│   └── server/StatementSourceControllerTest.java
├── .github/workflows/ci.yml      (mvnw test on Temurin 25)
├── mvnw, mvnw.cmd, pom.xml
├── launch.cmd                    (Windows launcher; expects a bundled Maven)
├── pig-purchases-db.mv.db        (H2 database file, auto-created, git-ignored)
├── pig-purchases-data.txt        (legacy seed data; migrated once, git-ignored)
└── README.md (this file)
```

> Note: a Python `.venv` plus `import_budget.py` / `create_icon.py` are one-off
> helper scripts (spreadsheet import, icon generation), not part of the running
> app. `budget-import.json` and the data/db files hold personal amounts and are
> git-ignored.

## Next Steps

1. **Configure `ANTHROPIC_API_KEY` and re-run the June mapping.** The AI pass is
   built but has never run against real data; this is the step that tells us what
   recall actually looks like. Writing prose hints on the budget entries (the AI
   reads them) and `match:` lines for recurring merchants (`FRESHMARKET`,
   `FRESHMARKET`, `DAILYGRIND`, `HPK`/Harbor Park, `KP SCAL`) both raise it
   further — the parked bucket on the Mapping screen shows which to write.
2. **Monthly analysis.** Total actual spend vs. total budget with variance, plus
   the per-entry breakdown, driven by a completed run — including "Other".
3. Surface parser selection + exclusions in the statement-source UI, so a source
   created in the app can actually be ingested.
4. Month-over-month comparison, rolling averages, charts.
5. Export / reporting.
6. Additional statement formats (CSV, OFX) as needed.
