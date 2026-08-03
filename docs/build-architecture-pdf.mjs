/*
 * Render docs/architecture.html to docs/ARCHITECTURE.pdf.
 *
 *   node docs/build-architecture-pdf.mjs
 *
 * Needs Node and Chrome, both of which are already on this machine. It is deliberately NOT
 * wired into the Maven build: the PDF is a hand-checked deliverable, not a build artifact, and
 * a `mvn test` that silently shells out to a browser is a worse trade than remembering one
 * command.
 *
 * Two things here are load-bearing and look redundant until you remove them.
 *
 * The mermaid bundle is INLINED rather than linked. The page is printed from a file:// URL, so
 * a CDN <script> would fail silently and produce a perfectly valid PDF with six empty boxes
 * where the diagrams should be — the worst possible failure, because it looks finished.
 *
 * And the script waits for mermaid to signal completion rather than trusting a timeout.
 * Diagrams render asynchronously; Chrome prints whatever is on screen when the virtual clock
 * runs out. The verify step below then counts the SVGs and fails loudly if any diagram did not
 * make it, because "the PDF was written" and "the PDF is correct" are different claims.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const docs = dirname(fileURLToPath(import.meta.url));
const source = resolve(docs, 'architecture.html');
const output = resolve(docs, 'ARCHITECTURE.pdf');
const staging = resolve(docs, '.architecture.print.html');

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  '/usr/bin/google-chrome',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
];

function findChrome() {
  const found = CHROME_CANDIDATES.find((p) => existsSync(p));
  if (!found) {
    throw new Error('No Chrome or Edge found. Checked:\n  ' + CHROME_CANDIDATES.join('\n  '));
  }
  return found;
}

function findMermaid() {
  // Wherever npm put it — docs/node_modules or the repo root.
  for (const base of [docs, resolve(docs, '..')]) {
    const p = resolve(base, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js');
    if (existsSync(p)) return p;
  }
  throw new Error('mermaid is not installed. Run:  npm install --prefix docs mermaid@11');
}

const runtime = (mermaidJs) => `
<script>${mermaidJs}</script>
<script>
  mermaid.initialize({
    startOnLoad: false,
    theme: 'base',
    themeVariables: {
      background: '#f6f8fa', primaryColor: '#ffffff', primaryTextColor: '#243039',
      primaryBorderColor: '#b6560c', lineColor: '#5b6873',
      secondaryColor: '#f3e7dc', tertiaryColor: '#eef2f6',
      fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif'
    },
    flowchart: { htmlLabels: true, useMaxWidth: true },
    er: { useMaxWidth: true },
    state: { useMaxWidth: true }
  });
  mermaid.run({ querySelector: 'pre.mermaid' })
    .then(function () { document.title = document.title + ' \\u2713'; })
    .catch(function (e) { window.__mermaidError = String(e); });
</script>
`;

function build() {
  const chrome = findChrome();
  const html = readFileSync(source, 'utf8') + runtime(readFileSync(findMermaid(), 'utf8'));
  writeFileSync(staging, html, 'utf8');

  const url = 'file:///' + staging.replace(/\\/g, '/');
  const common = [
    '--headless', '--disable-gpu', '--no-sandbox',
    '--virtual-time-budget=45000', '--run-all-compositor-stages-before-draw',
  ];

  // Verify before printing: count the SVGs mermaid actually produced. Printing first and
  // checking afterwards would mean parsing a PDF to find out, which is the hard way round.
  const dom = execFileSync(chrome, [...common, '--dump-dom', url],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });

  const expected = (readFileSync(source, 'utf8').match(/<pre class="mermaid">/g) || []).length;
  const rendered = (dom.match(/<svg[^>]*id="mermaid-/g) || []).length;
  if (rendered !== expected) {
    throw new Error(
      `Only ${rendered} of ${expected} diagrams rendered. The PDF would look finished and be `
      + `wrong, so nothing was written. Check the mermaid syntax in docs/architecture.html.`);
  }

  // Counting SVGs is NOT enough on its own, which this guard learned the hard way. A diagram
  // mermaid cannot parse still produces an <svg id="mermaid-…"> — it draws a bomb icon and the
  // words "Syntax error in text" — so the count came back 6 of 6 and a PDF containing an error
  // graphic was written and reported as a success. The failed ones are marked instead.
  const broken = (dom.match(/aria-roledescription="error"/g) || []).length;
  if (broken > 0) {
    throw new Error(
      `${broken} diagram(s) failed to parse and mermaid drew its error graphic in their place. `
      + `Nothing was written. Search docs/architecture.html for the diagram that changed.`);
  }

  execFileSync(chrome, [...common, '--no-pdf-header-footer', `--print-to-pdf=${output}`, url],
    { stdio: ['ignore', 'ignore', 'ignore'] });

  unlinkSync(staging);
  const bytes = readFileSync(output).length;
  console.log(`docs/ARCHITECTURE.pdf — ${expected} diagrams, ${(bytes / 1024).toFixed(0)} KB`);
}

build();
