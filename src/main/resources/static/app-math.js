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
 * The TYPE decides, not the stored sign, because the parsers disagree: a Bayside withdrawal is
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

/** "2026-07-22T15:53:38.53" -> "07/22 15:53:38". Also string-only, for the same reason. */
function formatLogTime(iso) {
  if (!iso) return '';
  const m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  return m ? `${m[2]}/${m[3]} ${m[4]}:${m[5]}:${m[6]}` : iso;
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
