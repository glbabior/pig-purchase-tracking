# Quick start

A desktop budget tracker: point it at folders of bank and credit-card statement PDFs,
and it turns them into categorized spending against your budget. Everything runs on
your machine.

**You need a JDK 25 on `PATH`.** Nothing else — the Maven wrapper is committed, so
there is no Maven to install.

---

## Start here

```powershell
.\demo.cmd
```

Wait for `Started PigPurchasesApplication` (about 5 seconds), then open
<http://localhost:8080> yourself — the server does not open it for you. Stop with
`Ctrl+C`, or `.\stop.cmd` from another terminal.

Run it from **your own terminal**, not a service or a scheduled task. That is what
lets "open in Acrobat" hand a statement to your already-running Acrobat.

---

## `demo.cmd` vs `launch.cmd`

|  | `.\demo.cmd` | `.\launch.cmd` |
|---|---|---|
| Statements | Three months, generated for you, each spending a little differently | Your own PDFs |
| Database | `~/.pigpurchases-demo` — disposable | `~/.pigpurchases` — the real one |
| Backups | Off, and redirected | On, to `~/pigpurchases-backups` |
| Claude API | Off, so it costs nothing | On if a key is present |
| Ready to use? | **Immediately** | Not until you configure it |

**Use `demo.cmd` to look around.** It invents a bank that does not exist —
*Northwind* — in a statement format this project invents, so there is something real
to load, map and total without any data of your own. Delete `~/.pigpurchases-demo`
to start over.

**`launch.cmd` is for your actual money**, and a fresh clone cannot ingest anything
with it yet: you would need to add a statement source *and* write a parser for your
bank's PDF layout, since no real bank's format ships here. `NorthwindStatementParser`
is the worked example. See the README when you get there.

The two share nothing — separate databases, separate backup directories. The demo
cannot touch real data.

---

## What you will find, left to right

The sidebar is roughly the order you would use it in.

| Screen | What it is |
|---|---|
| **Budget Entries** | Your categories, each an amount × a quantity. The optional *hints* field is what teaches categorization. |
| **Matching Hints** | Every hint in one place, per category — which ones catch nothing, which are ignored and why, and a preview before you save one. |
| **Statement Sources** | Your accounts, each with the folder its statements live in. Add, edit, delete, or open the folder. |
| **Ingest** | Load a statement from a source's folder. Everything loaded is listed below, newest first. Also where manual transactions go — cash, a Venmo balance. |
| **Mapping** | One row per loaded statement. Categorizes transactions; anything it cannot place is *parked* as "Other" for you to sort out. |
| **Spend: Monthly** | Budget vs. actual for one calendar month. Click a category to see the transactions behind it. |
| **Spend: Rolling** | The typical month — an average across the months you marked complete, plus trend charts drawn from those same months. |
| **Settings** | Annual budget, backups, restore. |
| **Debug** | What the app did behind the scenes. Where to look when a total moved and you don't know why. |

Every screen has a **? Help** button, and the sidebar has a full guide.

---

## The five-minute tour

After `.\demo.cmd`, work left to right:

1. **Ingest** — click **Load statement…** on the Northwind source, pick a PDF.
   Watch it become transactions.
2. **Mapping** — click **Map Transactions**. Most rows land in a category from the
   seeded hints. A few do not.
3. **Mapping → the parked bucket** — three rows per statement sit in "Other" on
   purpose: a hardware shop, a florist, and a card payment. Categorize one and the
   app remembers that merchant forever.
4. **Spend: Monthly** — the totals, with every category clickable.

The card payment in step 3 is the one worth pausing on. It sits in "Other" without
adding its amount to "Other", because money coming *in* is not spend unless you say
so. That rule is most of why this app exists rather than a spreadsheet.

---

Fuller detail — the categorization passes, the privacy boundary, backups and
restore — is in [README.md](README.md). The design, with diagrams, is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
