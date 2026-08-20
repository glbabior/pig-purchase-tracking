/*
 * The frontend's pure functions: money, dates, and escaping.
 *
 * These are here rather than inline in index.html for exactly one reason — so they can be
 * TESTED. They are the code that decides what number appears on screen, and until this
 * file existed none of it was covered by anything, while every equivalent on the Java side
 * had a test that was checked to fail when reverted.
 *
 * Nothing else changes: the page still loads this as a plain <script>, the browser gets the
 * file byte-for-byte as written, and editing it still needs only a refresh. There is no
 * bundler and no build step for the frontend.
 *
 * Keep this file free of DOM access and fetch(). Anything that touches the page belongs in
 * index.html; anything that computes a value belongs here, where AppMathTest can reach it.
 */

/**
 * Money out is positive, money in negative — the mirror of AnalysisService.signedSpend.
 *
 * The TYPE decides, not the stored sign, because the parsers disagree: a deposit-account withdrawal is
 * stored negative while a Crestline purchase is positive. "Review all" used to render
 * Math.abs(amount), so a 150 refund read as a 150 charge there and as -150 in the category
 * drill-down — the same transaction with opposite signs on the two screens where it gets
 * acted on. Assigning that "charge" to Groceries made Groceries go down.
 *
 * AppMathTest asserts this agrees with the Java implementation for every type, so the two
 * cannot drift apart and leave the screen disagreeing with the database.
 */
function signedSpend(row) {
  const magnitude = Math.abs(Number(row.amount || 0));
  const moneyIn = row.type === 'PAYMENT' || row.type === 'CREDIT' || row.type === 'DEPOSIT';
  return moneyIn ? -magnitude : magnitude;
}

/** Always two decimals, so the page never shows a truncated or drifting cent. */
function formatCurrency(value) {
  const numericValue = Number(value || 0);
  return '$' + numericValue.toFixed(2);
}

/**
 * Parse a typed amount, or null when it is not one. Deliberately strict: at most two
 * decimals, no negatives, no exponents. Returning null rather than NaN is what lets the
 * caller refuse the save instead of writing a broken number.
 */
function parseDollarAmount(value) {
  const trimmed = String(value).replace(/\$/g, '').replace(/,/g, '').trim();
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  return Number(trimmed).toFixed(2);
}

/**
 * "2026-06-11" -> "06/11/26". Works on the ISO string, never through Date.
 *
 * Shape-checked rather than merely split on "-": counting hyphens let any two-hyphen
 * string through, so "not-a-date" came back as "a/date/t" instead of passing through
 * unchanged. Anything that is not an ISO date is returned as-is, so a value the server
 * did not expect shows up recognisably rather than as plausible-looking nonsense.
 */
function formatMmDdYy(iso) {
  if (!iso) return '??';
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso));
  return m ? `${m[2]}/${m[3]}/${m[1].slice(2)}` : String(iso);
}

/**
 * "2026-06" -> "June 2026".
 *
 * Parsed from the string rather than via `new Date(month)`, which interprets a bare
 * YYYY-MM-DD as UTC and would show the previous month for anyone west of Greenwich.
 */
function formatMonthLong(month) {
  const m = /^(\d{4})-(\d{2})$/.exec(month || '');
  if (!m) return month || '';
  const names = ['January', 'February', 'March', 'April', 'May', 'June',
                 'July', 'August', 'September', 'October', 'November', 'December'];
  const idx = Number(m[2]) - 1;
  return names[idx] ? `${names[idx]} ${m[1]}` : month;
}

/**
 * The monthly total an entry save should carry. The edit dialog shows
 * total ÷ quantity rounded to cents, so multiplying back can lose a cent —
 * 100.00 at quantity 3 shows 33.33 and would save 99.99. Harmless drift once,
 * but budget edits are recorded as eras now, so that phantom cent would be
 * written into history as a change the user never made (and could overwrite an
 * era they had just fixed). When neither the per-unit amount nor the quantity
 * was touched, the exact stored total passes through unchanged; `opened` is a
 * snapshot of what the dialog displayed ({total, perUnit, quantity}), null when
 * adding a new entry.
 */
function monthlyTotalForSave(perUnit, quantity, opened) {
  if (opened && String(perUnit) === opened.perUnit && String(quantity).trim() === opened.quantity) {
    return opened.total;
  }
  return (Number(perUnit) * Number(quantity)).toFixed(2);
}

/**
 * The current calendar month as the "YYYY-MM" token the server keys eras by.
 * Built from local date parts, never toISOString() — that renders UTC, and west of
 * Greenwich the evening of the month's last day would report the wrong month.
 */
function currentLocalMonth(now) {
  const d = now || new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

/** True when the value is a well-formed "YYYY-MM" month token. */
function isValidMonthToken(value) {
  return /^\d{4}-(0[1-9]|1[0-2])$/.test(String(value || ''));
}

/** "2026-07-22T15:53:38.53" -> "07/22 15:53:38". Also string-only, for the same reason. */
function formatLogTime(iso) {
  if (!iso) return '';
  const m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  return m ? `${m[2]}/${m[3]} ${m[4]}:${m[5]}:${m[6]}` : iso;
}

/**
 * Sort table rows by one named column.
 *
 * `sort` is {key, dir}; `valuesByKey` maps a column key to what that column compares on. An
 * unknown or absent key returns the input untouched, which is the server's order.
 *
 * Rows with nothing to compare SINK, whichever way the column is sorted, rather than
 * flipping to the top on the reverse click. Sorting by "Parked" asks a question about mapped
 * statements; an unmapped one is not the answer at either end, and interleaving them makes
 * the column unreadable.
 *
 * Returns a new array, so the caller keeps the server's order to sort differently next time.
 */
function sortRows(rows, sort, valuesByKey) {
  const value = valuesByKey[(sort || {}).key];
  if (!value) return rows;
  const dir = (sort.dir === -1) ? -1 : 1;
  return [...rows].sort((a, b) => {
    const x = value(a);
    const y = value(b);
    const xMissing = x === null || x === undefined;
    const yMissing = y === null || y === undefined;
    if (xMissing || yMissing) return (xMissing && yMissing) ? 0 : (xMissing ? 1 : -1);
    return (typeof x === 'number' ? x - y : String(x).localeCompare(String(y))) * dir;
  });
}

/**
 * Mapping columns. Returning null rather than 0 for an unmapped statement is the point: it
 * has no counts, and "not mapped yet" is a different fact from "mapped, nothing parked".
 * Dates are ISO strings, so they compare correctly as text.
 */
const RUN_SORT_VALUES = {
  sourceName: (f) => String(f.sourceName || '').toLowerCase(),
  statementDate: (f) => f.statementDate || null,
  transactionCount: (f) => f.transactionCount,
  mappedAt: (f) => f.mappedAt || null,
  mappedCount: (f) => (f.mapped ? f.mappedCount : null),
  parkedCount: (f) => (f.mapped ? f.parkedCount : null),
  excludedCount: (f) => (f.mapped ? f.excludedCount : null),
};

/**
 * Matching Hints columns, over the per-category rows the screen builds.
 *
 * "Needs attention" is not a number on screen, so it sorts on severity — descending puts the
 * categories worth looking at first. A rule the app cannot honour outranks one that is valid
 * but has matched nothing yet, which outranks a category with no rules at all: the first can
 * never work, the second may just be waiting for a statement, and the third is only a gap.
 */
const HINT_SORT_VALUES = {
  name: (r) => String(r.name || '').toLowerCase(),
  hintCount: (r) => r.hintCount,
  matched: (r) => r.matched,
  attention: (r) => (r.ignored ? 3 : r.idle ? 2 : r.hintCount ? 0 : 1),
};

/**
 * A statement source and where its statements live. Shared by the two screens that list
 * sources — Statement Sources, and the top table on Ingest — because both sort the same two
 * columns of the same objects, and two copies would be free to disagree.
 */
const SOURCE_SORT_VALUES = {
  name: (s) => String(s.name || '').toLowerCase(),
  folderPath: (s) => String(s.folderPath || '').toLowerCase(),
};

/**
 * Ingest, bottom table: every loaded statement across all sources.
 *
 * Dates are ISO strings, so they compare correctly as text — and a statement with no date
 * cannot exist, because a parser that cannot determine one refuses the import rather than
 * storing rows against no month. The From/To pair is the span of the statement's own
 * transaction dates; an import with no transactions has neither, and sinks.
 */
const INGEST_IMPORT_SORT_VALUES = {
  sourceName: (i) => String(i.sourceName || '').toLowerCase(),
  statementDate: (i) => i.statementDate || null,
  firstTransactionDate: (i) => i.firstTransactionDate || null,
  lastTransactionDate: (i) => i.lastTransactionDate || null,
};

function sortRunFiles(files, sort) { return sortRows(files, sort, RUN_SORT_VALUES); }
function sortHintCategories(rows, sort) { return sortRows(rows, sort, HINT_SORT_VALUES); }
function sortSources(rows, sort) { return sortRows(rows, sort, SOURCE_SORT_VALUES); }
function sortIngestImports(rows, sort) { return sortRows(rows, sort, INGEST_IMPORT_SORT_VALUES); }

/**
 * Group a category trend's months into runs of equal budget — the eras as the chart
 * shows them. Each group carries its month span, the budget in force, the average
 * actual across its months, and the average variance (budget − avg actual), so the
 * per-era breakdown under the chart can say "vs $230 for May–Jul: under by $2/mo".
 *
 * Consecutive equality, not global: a budget changed and later changed back is two
 * separate eras on screen, which is what the timeline reader expects.
 */
function budgetEraGroups(points) {
  const groups = [];
  (points || []).forEach((p) => {
    const budget = Number(p.budget) || 0;
    const actual = Number(p.actual) || 0;
    const last = groups[groups.length - 1];
    if (last && last.budget === budget) {
      last.toMonth = p.month;
      last.months += 1;
      last.totalActual += actual;
    } else {
      groups.push({ fromMonth: p.month, toMonth: p.month, budget, months: 1, totalActual: actual });
    }
  });
  groups.forEach((g) => {
    g.avgActual = Math.round((g.totalActual / g.months) * 100) / 100;
    g.variance = Math.round((g.budget - g.avgActual) * 100) / 100;
    delete g.totalActual;
  });
  return groups;
}

/**
 * Escape text for interpolation into markup. Statement descriptions are merchant-controlled
 * text, so this is the boundary between them and the page.
 *
 * Note it does not escape "'". Every interpolated attribute in index.html is double-quoted,
 * and `"` is escaped — but that is an invariant of the caller, not of this function.
 */
function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
