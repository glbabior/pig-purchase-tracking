# PigPurchases — working agreements

## Documentation upkeep (standing rule)

Any **functional** change — behavior, API surface, schema, or an invariant —
is not done until the docs have been checked against it:

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

## Invariants to know before changing anything

- `AnalysisRun.month` is **not** a calendar month — it is the internal token
  `import-{id}`. Calendar grouping comes from each transaction's actual date.
- `TransactionMapping.countsAsSpend()` is the single authority on whether a row
  counts as spend. Ask it; never compare statuses directly.
- **Parked counts as spend.** Both flavours of excluded do not.
- Schema is `ddl-auto=update`, which only ever **adds**. New enum constants and
  new non-null columns are the known trap — see `EnumColumnMigration`.
- Most inter-table links are plain `Long` id fields with **no FK constraints**.
  Referential integrity is enforced in application code, not the database.

## Testing

`*ValidationTest` reconciles the parsers against real statements on this machine
and skips silently elsewhere. **A green CI run does not prove the parsers still
reconcile** — run the suite locally after touching a parser.
