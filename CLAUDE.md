# PigPurchases — working agreements

## Documentation upkeep (standing rule)

Any **functional** change — behavior, API surface, schema, or an invariant —
is not done until the docs have been checked against it:

- `README.md` — the API reference, the Working / Known gaps / Planned lists, and
  the `_Code-verified <date>_` stamp
- `docs/ARCHITECTURE.md` — diagrams, the layer map, and the invariants in §7
- `docs/architecture.html` — the **shareable** version of the same material, with
  rendered diagrams. It is the source for both the published artifact and the PDF.

### The architecture document has three copies. Update all three.

`ARCHITECTURE.md`, `architecture.html`, and the PDF built from it say the same
things in three places. Only the markdown was in this checklist, so the HTML went
**a week** out of date without anyone noticing — long enough to show a status enum
missing a constant and a privacy claim that was no longer true.

After changing `docs/architecture.html`:

```
node docs/build-architecture-pdf.mjs      # rewrites docs/ARCHITECTURE.pdf
```

It refuses to write the PDF if any diagram fails to render, because mermaid draws a
"Syntax error" graphic in place of a broken diagram and the result otherwise looks
finished.

Then republish the artifact so the shared link matches — same URL, passed as `url`:
`https://claude.ai/code/artifact/85a5e20c-a856-4beb-9095-819f2a91c858`

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
