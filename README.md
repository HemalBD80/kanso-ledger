# Kanso Ledger — 100% Local Android Money Tracker (BDT)

**Kanso Ledger** is a native Android personal money tracker written in **Kotlin** and **Jetpack Compose**, engineered with **100% local on-device SQLite persistence (AndroidX Room)** and a tactile **Swiss editorial paper & Obsidian Ink** UI. Every Money added, every money spent, and every generated report lives exclusively on the phone: no cloud, no network permission (`android.permission.INTERNET` is intentionally absent), no demo data.

Currency is **Bangladeshi Taka (৳)** by default and everything starts at **৳0.00** on a fresh install so testing always begins from a clean, empty ledger.

---

## 1. Overview

- **Platform**: Android 8.0+ (minSdk 26), Kotlin 2.0.21, Jetpack Compose, Material 3
- **Persistence**: 100% on-device Room + SQLite. No cloud, no remote APIs, no analytics, no `INTERNET` permission
- **Balance rule**: `Available Balance = Opening Balance + Money Added − Expenses`
- **Entry UI**: oversized tactile 4×4 keypad with live inline math (`14.50 + 3.20` → `৳17.70`), haptics, merchant auto-complete
- **Reports**: print-friendly **PDF** + **CSV** export via the Android Storage Access Framework — generated entirely on-device
- **Data discipline**: all currency stored in integer minor units (poisha) and rendered with `fontFeatureSettings = "tnum"` tabular numerals

## 2. Features

### 2.1 Money In / Add Money
- Dedicated **ADD MONEY** action with sources: Initial Balance, Salary / Income, Freelance, Gift, Refund, Transfer Received, Other
- Each addition is its own transaction row — earlier balances/additions are never overwritten
- Amount (keypad), date (picker; time = save time), source, payment method/provider, optional note
- Refunds count **once** as money added, never also as an expense reduction

### 2.2 Expense Recording & Balance
- Keypad with inline math, category pills, payment methods, recurring toggle, notes
- Payment methods: **Cash, Debit Card, Credit Card, Mobile Wallet (bKash / Nagad / Rocket), Bank Transfer, Other** — with an optional wallet/bank/provider name field
- Available balance + this-month money-in + this-month expenses update immediately after every save
- Every figure on the dashboard is traceable to the transactions behind it via filters + the local audit trail

### 2.3 Category Management
- Add **custom categories** (name, visual identifier key, editorial color)
- **Rename** any category — renames only change the display name, so historical transactions keep their link
- **Delete** custom categories with a safe prompt: reassign linked transactions to another category, or archive them under **Other** — history is never lost
- Built-in defaults: Food & Dining, Groceries, Transportation, Housing & Rent, Shopping, Health, Education, Entertainment, Bills & Utilities, Subscriptions, Other (plus legacy v1.0 ids that stay valid)
- Each category shows its all-time transaction count and total

### 2.4 Reports (PDF + CSV)
- Date ranges: **Today, Yesterday, Last 7 Days, This Week (Mon–Sun), Last Week, This Month, Last Month, Custom Range**
- Contents: report title & range, opening balance, money added, total expenses, closing balance, transaction count, line-by-line transactions (date, time, category/source, description, payment method, provider, amount), category-wise breakdown, payment-method-wise breakdown
- **PDF**: paged A4 document via `android.graphics.pdf`; **CSV**: RFC-4180-friendly `text/csv` for spreadsheets
- Saved through Android's Storage Access Framework ("Create Document") — the user picks the location; still zero network

### 2.5 Financial History & Audit Trail
- Unified ledger of expenses **and** money-in, grouped by day with in/out day headers
- Filters: **type** (All / Money In / Expenses), **category**, **payment method**, and text search; month navigation covers the date axis
- Local **audit trail** records every added / edited / deleted transaction and category in the Local Vault sheet, so users can understand why a balance changed

### 2.6 Budgets & Dashboard
- Monthly cap + per-category caps — **both default ৳0.00** on a fresh install or an empty budget form (no hidden fallbacks)
- Dashboard: available balance, money in/out this month, remaining budget, pacing status, daily spend rhythm (custom Canvas chart), category & payment-method breakdowns, action row (**Add Money**, **Record Expense**, **Manage Categories**, **Download Report**)

### 2.7 Currency
- **BDT (৳) is the default currency** and appears in every figure across every screen and report
- Selector also offers $ / € / £ / ₹ / ¥ / ₩ in the Budget planner; the symbol only changes display, never stored minor units

## 3. Screens / workflows

```
Dashboard
├── Balance strip (available balance, money in • month, expenses • month)
├── Action row: ADD MONEY • MANAGE CATEGORIES • DOWNLOAD REPORT   (+ RECORD EXPENSE FAB)
├── Hero ledger card (outflow, pacing, monthly cap, remaining, safe/day, daily avg)
├── Daily spend rhythm chart (canvas) + dashed daily target
├── Filters: type • category • payment method • search
├── Payment-method-wise spending card
└── Day-grouped ledger (expenses −, money-in +, one row each, editable)
Sheets: TactileExpenseSheet • MoneyInSheet • CategoryManagerSheet • ReportSheet • BudgetPlannerSheet • LocalVaultSheet
```

## 4. Tech stack & requirements

- **Kotlin** 2.0.21 · **Compose BOM** 2024.11.00 · **Material 3**
- **AndroidX Room** 2.6.1 + KSP (schema export enabled)
- **AGP** 8.7.2 · **Gradle** 8.9 · compileSdk/targetSdk 35 · minSdk 26
- **JDK 17** · **Android SDK platform 35 + build-tools 35.0.0**
- Ubuntu typeface family (UFL v1.0) bundled in `res/font/`

## 5. Project structure

```text
/app/src/main/java/com/kanso/spending/
├── KansoApplication.kt · MainActivity.kt
├── data/
│   ├── model/           SpendingCategory (legacy seed), PaymentMethod, MoneySource
│   ├── local/
│   │   ├── entity/      ExpenseEntity (+payment_provider), MonthlyBudgetEntity, CategoryBudgetEntity,
│   │   │                CategoryEntity, IncomeEntity, AuditLogEntity
│   │   ├── dao/         SpendingDao
│   │   ├── KansoDatabase.kt          (version 2, MIGRATION_1_2: categories + income_entries + audit_log)
│   │   └── LocalBackupManager.kt     (JSON snapshot schema v2)
│   ├── report/ — (report builders live in domain/, see below)
│   └── repository/      SpendingRepository (audit-logged writes, category ops, seed)
├── domain/
│   ├── CurrencyFormatter.kt (৳ default) · KeypadExpressionEvaluator.kt
│   ├── SpendingModels/Engine         (models + pure analytics)
│   ├── LedgerReportBuilder.kt        (ranges + pure report build)
│   ├── LedgerReportCsvWriter.kt      (pure CSV)
│   └── LedgerReportPdfWriter.kt      (on-device PDF)
└── ui/
    ├── SpendingViewModel.kt         (combined state, reports, balance)
    ├── theme/ Color, Type, Theme    (KansoExtendedColors + fjord/ochre accents)
    ├── components/  EditorialHeaderBar, BalanceStrip & ActionRow & PaymentMethodSpendCard (in screen file),
    │                HeroLedgerCard, DailyRhythmChart, TransactionLedgerItem (+MoneyInLedgerRow),
    │                TactileExpenseSheet, MoneyInSheet, CategoryManagerSheet, ReportSheet,
    │                BudgetPlannerSheet, LocalVaultSheet
    └── screens/     SpendingDashboardScreen.kt
/app/src/test/java/com/kanso/spending/
├── domain/SpendingAnalyticsEngineTest.kt    (totals, money-in, zero-budget, method breakdown)
├── domain/LedgerReportBuilderTest.kt        (ranges, balances, refunds, duplicates, CSV reconcile)
├── domain/CategoryIntegrityTest.kt          (seed integrity, ids, week boundaries)
├── domain/KeypadExpressionEvaluatorTest.kt
└── data/model/PaymentMethodTest.kt          (method list + legacy id compat)
```

## 6. Balance & data model rules

- Every amount is a `Long` in minor units (poisha). Display diverges only through `CurrencyFormatter`.
- `Available Balance = opening-balance records + all other money-in − all expenses`, computed across **all history**, so any possible edit/delete updates it consistently.
- Refunds are money-in (`MoneySource.REFUND`) — counted once; they never reduce expenses.
- Transfers between accounts: recorded money-in is labeled "Transfer Received"; true account-level tracking (multiple accounts) is **not** implemented — see limitations.
- Renaming a category changes only `categories.display_name`; the stable id keeps `expenses.category_id` valid. Only custom (`custom_*`) categories can be deleted, with reassignment or archive to **Other**.
- Audit trail entries are inserted on every ledger/category mutation and on vault wipe/restore.

## 7. Local database & migration

- Room version **2**. Migration `1 → 2` is **non-destructive**: creates `categories` (seeded with the full default directory), `income_entries`, `audit_log`, and adds `expenses.payment_provider`. A fresh install gets the same defaults via an idempotent seed in the ViewModel init.
- **Backup JSON schema v2** adds `incomes` and `categories`; v1 snapshots still restore. Restore validates amounts/currency, maps unknown category ids to "Other", preserves record ids for exact round-trip, and never invents data.
- **Wipe** clears every table and re-seeds the default categories (never leaves a broken picker).

## 8. Building from source

1. **JDK 17**
2. **Android SDK** with `platforms;android-35`, `build-tools;35.0.0`
3. `./gradlew test` — run the unit tests
4. `./gradlew assembleRelease` — produce the APK at `app/build/outputs/apk/release/`

> **Local host note**: this workspace is aarch64 Linux while Google's `aapt2`/`d8`/`r8` launchers ship x86-64 only, so builds happen in CI. Use the GitHub Actions workflow below.

## 9. GitHub Actions build & release

`.github/workflows/build-apk.yml` (single workflow, no duplicates):

1. `./gradlew test` — unit tests run **before** any packaging; failures fail the job loudly ("Build & Publish APK").
2. `./gradlew assembleRelease` with JDK 17 on an `ubuntu-latest` runner.
3. APK uploaded as artifact `kanso-ledger-apk` for direct device testing.
4. **Publish** (tag push `v*` or manual dispatch): creates a GitHub Release with a versioned asset (`KansoLedger-<tag>.apk`); non-tag dispatches are marked **pre-release** (beta channel).
5. No signing secrets are stored in the repo; the release build reuses the debug keystore. There are **no** release upload-keystore credentials configured — this is disclosed rather than hidden. To distribute a properly release-signed APK, add `keystore/` + secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) to GitHub and extend the workflow.

**Release steps**: bump `versionName`/`versionCode` in `app/build.gradle.kts` → push to `main` → `git tag v2.x && git push origin v2.x` → workflow attaches `KansoLedger-v2.x.apk` to the release.

## 10. Downloading & installing the APK

> **📌 Safe working v1**: the `v1.0.0` GitHub Release is marked **"SAFE WORKING v1"** — it is the verified, known-good baseline build (empty ledger, ৳0.00 defaults, BDT default, zero-network). If a newer build ever misbehaves, return to that release. Later prereleases (v1.1.0-beta*, v1.2.0-build*) build on top of it and are labeled pre-release.

1. Open the repository's **Releases** page.
2. Download `KansoLedger-<version>.apk` (or grab the Actions artifact for untested intermediate builds).
3. On an Android device, allow " Install unknown apps" for your browser/file manager and install.
4. First launch opens an **empty** ledger: balance ৳0.00, no entries. Start with **ADD MONEY → Initial Balance**, then **RECORD EXPENSE**.

## 11. Running tests

```bash
./gradlew test
```

Covers: analytics (totals, money-in, zero-budget truthfulness, method breakdown), report ranges & boundaries (Today/Yesterday/Last 7/weeks/months/custom), balance arithmetic, refund single-count semantics, no duplicate transactions in reports, CSV totals reconciliation, category seed integrity & stable ids, payment-method list + legacy id mapping, BDT formatting, keypad math.

Not covered (disclosed): Room migration `1→2` lives on Android — verifying it requires instrumented/Robolectric tests not present here; PDF rendering is exercised only by the CI build (an Android `PdfDocument` cannot be unit-tested on JVM).

## 12. Limitations

- **Single-account ledger**: transfers are labeled but not netted against separate account balances; account-level multi-account support is future work.
- Money-in entries capture the day via a picker; the time is the save time (no time-of-day editing).
- Built-in default categories cannot be deleted (they can be renamed or hidden by design) — custom categories get full rename/delete/reassign.
- PDF tests: the writer is exercised by CI build only; CSV/logic paths are unit-tested.
- Node.js deprecation warnings in CI are from GitHub Actions tooling, not the project.

## 13. Contributing

- Keep the app **zero-network**, **single-database**, **no-demo**. No seed transactions, no placeholders, no remote calls.
- Currency is always internal minor units; format through `CurrencyFormatter`.
- Category changes must go through `SpendingRepository` so the audit trail and stable-id invariants hold.
- Prefer Material 3 + `LocalKansoColors` tokens over hard-coded hex values.

## 14. License & attributions

- Project code: Apache-2.0-compatible (AndroidX, Compose, Room, Material 3 are Apache-2.0).
- **Ubuntu typeface family** bundled in `res/font/` — **Ubuntu Font License (UFL v1.0)**.
- ৳ symbol rendering uses Android's system Bengali fallback fonts where the bundled Ubuntu family has no Bengali glyph.

---

*Crafted with a zero-cloud, editorial-ledger mindset. Keep your money on-device.*
