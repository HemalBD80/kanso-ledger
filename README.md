# Kanso Ledger — 100% Local Android Money Tracker (BDT)

**Kanso Ledger** is a native Android personal finance tracker written in **Kotlin** and **Jetpack Compose**, engineered with **100% local on-device SQLite persistence (AndroidX Room)** and a tactile **Swiss editorial paper & Obsidian Ink** UI. Every transaction, balance, forecast and report lives exclusively on the phone: no cloud, no network permission (`android.permission.INTERNET` is intentionally absent), no demo data, no ads, no subscriptions, no external AI.

Currency is **Bangladeshi Taka (৳)** by default and everything starts at **৳0.00** on a fresh install so testing always begins from a clean, empty ledger.

**v2.0 — Personal Finance Intelligence**: optional on-device **Smart SMS Tracking**, a **multi-account ledger** with transfers and provider reconciliation, a financial **calendar**, **recurring payments** with labelled forecasts, **savings goals**, **quick entry**, an optional **biometric app lock**, configurable **notifications**, and reports that separate confirmed facts from pending review.

---

## 1. Overview

- **Platform**: Android 8.0+ (minSdk 26), Kotlin 2.0.21, Jetpack Compose, Material 3
- **Persistence**: 100% on-device Room + SQLite. No cloud, no remote APIs, no analytics, no `INTERNET` permission
- **Net funds rule**: headline balance = `Σ (per-account balances)` where each account is `opening + money in − money out − fees + adjustments`. Transfers move money between accounts and never count as income or expense
- **Entry UI**: oversized tactile 4×4 keypad with live inline math (`14.50 + 3.20` → `৳17.70`), haptics, merchant auto-complete, plus a one-tap **Quick Entry** sheet
- **Reports**: print-friendly **PDF** + **CSV** export via the Android Storage Access Framework — generated entirely on-device, with fees, transfers, SMS-vs-manual origin, account balances, reconciliation differences and pending counts
- **Data discipline**: all currency stored in integer minor units (poisha) and rendered with `fontFeatureSettings = "tnum"` tabular numerals
- **Status model**: every record is *confirmed* or *pending review*. Unreviewed detections and forecasts **never** affect confirmed totals

### Permissions (and what each one is for)

| Permission | Why | Required? |
|---|---|---|
| `READ_SMS` | Read **your own** transaction messages for Smart SMS Tracking (opt-in, off by default) | No — paste/share-sheet import works without it |
| `RECEIVE_SMS` | Detect new transaction messages **while the feature is enabled** | No |
| `POST_NOTIFICATIONS` | Show local notifications (import counts, budget, reconciliation, recurring due) | No — everything works silently without it |
| `USE_BIOMETRIC` | Optional biometric app lock (privacy screen) | No |
| ~~`INTERNET`~~ | **Not present.** Nothing can leave the device | — |

**Google Play policy note**: apps that use `READ_SMS` must declare it in Play Console and pass policy review. In Kanso Ledger the SMS feature is optional, off by default, reads only to parse on-device, and is fully replaceable by manual paste or the Android share sheet.

## 2. Features

### 2.1 Smart SMS Tracking (optional, on-device)
- Off by default; enabling shows **why** access is needed before the system prompt
- Deterministic, hand-written parsers for **bKash, Nagad, Rocket, banks and cards** — pure Kotlin, no cloud ML, no model downloads, extensible rule tables
- Extracts: transaction type, amount, fee, datetime, reference id, provider, account hint, remaining balance
- **Two import modes**: *Review before save* (everything waits) and *Auto* (only high-confidence transactions save immediately). Ambiguous messages **always** wait for review — the app never guesses
- Review queue: confirm, edit amount, pick category/account/transfer endpoints, **save-as-rule** for the merchant, or reject. Rejection records nothing
- **Deduplication**: fingerprint = SHA-256 of `provider|ref|REF` when a reference id exists, otherwise `provider|sender|amount|type|timestamp/1000`. Identical amounts alone are never treated as the same transaction — redelivered broadcasts and history re-imports cannot double-record
- Date-filtered **historical import** with counts; **undo last import**; **clear SMS import history** (erases metadata + review queue, never your recorded transactions)
- **Paste** a single message or **share** text into the app from any app — zero SMS permission needed
- Privacy: message bodies live in memory only during parsing — **never stored, logged, notified, backed up or crash-reported**. Only structured fields and fingerprints reach the database
- Turning Smart Tracking off disables all message reading; manual tracking is untouched

### 2.2 Multi-account ledger, transfers & reconciliation
- Separate **Cash, Bank, Wallet, Card** accounts (seeded) plus custom accounts — each with its own `opening + in − out − fees + adjustments` balance
- **Transfers** move money between accounts (optional fee) and are excluded from income/expense by design; fees are real debits
- **Provider-reported balances**: paste what your bank/wallet app says — the app *compares*, shows the difference, and **never silently overwrites** your ledger. Adjustments are explicit, audited actions
- Available, ledger and credit-card balances are distinguished; net funds = sum of non-archived accounts
- Structural accounts can be archived (never deleted); custom accounts delete only at ৳0.00 with zero references
- **ATM withdrawal** parsed from SMS classifies as a *transfer*, not an expense

### 2.3 Financial calendar
- Month grid of confirmed money movement per day (in/out colouring, transaction counts), week & month summaries
- Pending detections appear as a **count only** — never as money
- Day tap shows that day's in/out/net and pending count

### 2.4 Recurring payments (forecast ≠ actual)
- Rules you create (or confirm from **detected patterns**) with interval and next-due date
- Next-30-days **forecast list** — every item labelled *estimate*, never written to the ledger
- Detection proposes from your confirmed history; **you confirm** to create a rule
- **Mark paid** records a real expense and advances the next date — that is what keeps actuals separate from forecasts
- Past-due rules are highlighted but not counted as spending

### 2.5 Savings goals
- Target, optional deadline, progress bar, contribution history with per-row delete
- Labelled **projections** at deadline (estimate, on-track/needs-per-month) — computed at read time
- Contributions are labelled records: they update progress **without changing account balances** — the money is wherever you keep it; no double counting
- Dashboard net worth counts accounts only

### 2.6 Quick entry
- Fast confirmed path: keypad amount → category → method → save (today, manual, confirmed)

### 2.7 App lock (optional)
- Hides the app behind fingerprint / face / device screen lock whenever it goes to background (rotation never re-prompts)
- Honest scope: it is a **privacy screen, not encryption** — it does not encrypt the database or protect files extracted from the device. If no biometric/credential is enrolled, the lock says so plainly instead of pretending

### 2.8 Notifications (configurable, privacy-conscious)
- Four independent toggles: new detections, budget threshold, reconciliation warnings, recurring due
- Content rule: counts, category names and amounts **you already recorded** — never message text
- Watermarked so the same situation never re-notifies; denied permission degrades silently to nothing

### 2.9 Money In / expenses / categories / budgets (core, unchanged)
- **ADD MONEY** with sources (Initial Balance, Salary, Freelance, Gift, Refund, Transfer Received, Other); refunds count once as money in
- Keypad expense entry with inline math, category pills, payment methods (Cash, Debit, Credit, Mobile Wallet, Bank Transfer, Other), recurring toggle, notes
- Category management: custom create / rename / safe delete-with-reassignment; built-in defaults keep stable ids
- Budgets default **৳0.00**; dashboard shows balance, month in/out, pacing, daily rhythm, breakdowns
- Audit trail on every mutation in the Local Vault sheet

### 2.10 Reports (PDF + CSV)
- Ranges: **Today, Yesterday, Last 7 Days, This Week (Mon–Sun), Last Week, This Month, Last Month, Custom**
- Totals: opening, money added (incl. refund breakdown), expenses, **fees**, closing, transfers in/out of the range, **transfer fees**, SMS-vs-manual record counts, pending detections ("not counted anywhere")
- Line items: date, time, type, category, money-in source, description, method, provider, **record origin (S/M)**, in/out, **fee**, running balance
- Sections: category breakdown, method breakdown, **transfers** (from → to), **current account balances**, **unresolved reconciliation differences**, pending review
- Saved through Android's Storage Access Framework — still zero network

## 3. Screens / workflows

```
Dashboard
├── Balance strip (available balance = net funds, money in • month, expenses • month)
├── Intelligence: NET FUNDS • ALL ACCOUNTS (+ pending badge, reconciliation warning) + observations
├── Action rows: ADD MONEY • MANAGE CATEGORIES • DOWNLOAD REPORT
│                SMART SMS • ACCOUNTS • CALENDAR • RECURRING • GOALS • QUICK ENTRY • SMART SETTINGS
├── Hero ledger card + daily spend rhythm chart
├── Filters: type • category • payment method • search
├── Payment-method-wise spending card
└── Day-grouped ledger (editable)
Sheets: TactileExpenseSheet • MoneyInSheet • CategoryManagerSheet • ReportSheet • BudgetPlannerSheet
        • LocalVaultSheet • SmartSmsSheet • AccountsSheet • CalendarSheet • RecurringSheet
        • GoalsSheet • QuickEntrySheet • SmartSettingsSheet
App lock overlay (optional) sits on top; the dashboard stays composed beneath it
```

## 4. Tech stack & requirements

- **Kotlin** 2.0.21 · **Compose BOM** 2024.11.00 · **Material 3** · **androidx.biometric 1.1.0**
- **AndroidX Room** 2.6.1 + KSP (schema export enabled)
- **AGP** 8.7.2 · **Gradle** 8.9 · compileSdk/targetSdk 35 · minSdk 26
- **JDK 17** · **Android SDK platform 35 + build-tools 35.0.0**
- Ubuntu typeface family (UFL v1.0) bundled in `res/font/`

## 5. Project structure

```text
/app/src/main/java/com/kanso/spending/
├── KansoApplication.kt · MainActivity.kt (FragmentActivity, app lock, share-import, notifications)
├── sms/                SmsHistoryReader (date-ranged inbox read), SmsBroadcastReceiver (goAsync + dedup)
├── notifications/      AppNotifier (channels, import notice, watermarked alerts — counts only)
├── data/
│   ├── model/          PaymentMethod, MoneySource, SpendingCategory (legacy seed)
│   ├── local/
│   │   ├── entity/      Expense/Income (+source, fee, account, fingerprint), Account, Transfer,
│   │   │                AccountAdjustment, SmsDetection, SmsMeta, RecurringRule, SavingsGoal,
│   │   │                GoalContribution, CategoryRule, ActivityRow + legacy entities
│   │   ├── dao/         SpendingDao (incl. ledger fingerprints, snapshots, account queries)
│   │   ├── KansoDatabase.kt          (version 3: MIGRATION_1_2 byte-identical + MIGRATION_2_3)
│   │   └── LocalBackupManager.kt     (JSON snapshot schema v3 — body-free, v1/v2 still restore)
│   └── repository/      SpendingRepository (accounts, transfers, ingest, approvals, rules, goals…)
├── domain/              pure Kotlin, JVM-testable
│   ├── sms/             SmsModels, SmsParsingRules, SmsParser, SmsIngestPlanner (dedup/mode routing)
│   ├── accounts/        AccountSnapshot, AccountBalanceEngine, AccountStateFactory (reconciliation)
│   ├── recurring/       RecurringDetector (patterns + labelled forecasts)
│   ├── calendar/        CalendarEngine · goals/ SavingsGoalEngine · insights/ InsightsEngine
│   ├── categorization/  keyword rules engine
│   ├── LedgerReportBuilder/CsvWriter/PdfWriter (fees, transfers, origin, balances, pending)
│   └── CurrencyFormatter (৳ default) · KeypadExpressionEvaluator
└── ui/
    ├── SpendingViewModel.kt  (coreUiState + SmartLedgerUiState, actions, reports)
    ├── SmartLedgerActions.kt (callback bundle for all intelligence features)
    ├── components/  … existing sheets … + SmartSmsSheet, AccountsSheet, CalendarSheet,
    │                RecurringSheet, GoalsSheet, QuickEntrySheet, SmartSettingsSheet
    └── screens/     SpendingDashboardScreen.kt (intelligence section + action row)
/app/src/test/java/com/kanso/spending/
├── domain/… (analytics, report ranges + v2 report contract, keypad, categories, insights)
├── domain/sms/…        SmsParserTest (fictional fixtures only), SmsIngestPlannerTest (dedup/modes)
├── domain/accounts/…   AccountStateFactoryTest, AccountBalanceEngineTest (fees, transfers, pending)
├── domain/recurring/…  RecurringDetectorTest · goals/ SavingsGoalEngineTest · calendar/ CalendarEngineTest
└── data/model/PaymentMethodTest.kt
```

## 6. Balance & data-model rules

- Every amount is a `Long` in minor units (poisha). Display diverges only through `CurrencyFormatter`.
- **Accounts**: each balance is `opening + in − out − fees + adjustments`, computed from confirmed history only. `PENDING` detections are excluded from confirmed balances but visible as `balanceIfApprovedCents`.
- **Transfers** reduce the source and increase the destination with zero net effect on income/expense totals; their fees are real debits on both the account engine and report balances.
- **Fees**: standalone fee rows store `amountCents = 0` and the fee in `fee_cents`; bundled fees stay on the row. Reports split expenses vs fees; balances subtract both.
- **Pending/rejected** detections live only in `sms_detections`. Forecasts (recurring, goals) are computed at read time and never persisted as ledger rows.
- **Reconciliation**: provider-reported balances are stored separately and compared (`difference = reported − ledger`); the ledger changes only through an explicit adjustment.
- Refunds are money-in (`MoneySource.REFUND`) — counted once; they never reduce expenses.
- Renaming a category changes only the display name; stable ids keep history valid. Audit entries are written on every ledger/category/account mutation and on vault wipe/restore.

## 7. Local database & migration

- Room version **3**. `MIGRATION_1_2` is byte-identical to the released v1.x migration. `MIGRATION_2_3` is **non-destructive**: adds accounts/transfers/adjustments/sms_meta/sms_detections/recurring/savings/goals/contributions/category_rules, backfills legacy rows to structural accounts (`wallet→acc_wallet`, `debit`/`credit`/`card→acc_card`, `transfer→acc_bank`, else→acc_cash), and seeds the four default accounts a fresh install also gets.
- **Backup schema v3** adds accounts, transfers, adjustments, recurring rules, goals, contributions, rules, sms metadata and detections. **No message bodies are ever in a backup.** v1 and v2 backups still restore (missing structures default, never invented).
- **Wipe** clears every table and re-seeds default categories + accounts (never leaves a broken picker).

## 8. Building from source

1. **JDK 17**
2. **Android SDK** with `platforms;android-35`, `build-tools;35.0.0`
3. `./gradlew test` — run the unit tests
4. `./gradlew assembleRelease` — produce the APK at `app/build/outputs/apk/release/`

> **Local host note**: this workspace is aarch64 Linux while Google's `aapt2`/`d8`/`r8` launchers ship x86-64 only, so builds happen in CI. Use the GitHub Actions workflow below. The pure Kotlin domain + data layer compiles and tests locally (JDK 17 + kotlinc, 141 tests); the Compose/Room-processor layer is verified by CI.

## 9. GitHub Actions build & release

`.github/workflows/build-apk.yml` (single workflow, no duplicates):

1. `./gradlew test` — unit tests run **before** any packaging; failures fail the job loudly ("Build & Publish APK").
2. `./gradlew assembleRelease` with JDK 17 on an `ubuntu-latest` runner.
3. APK uploaded as artifact `kanso-ledger-apk` for direct device testing.
4. **Publish** (tag push `v*` or manual dispatch): creates a GitHub Release with a versioned asset (`KansoLedger-<tag>.apk`); non-tag dispatches are marked **pre-release** (beta channel).
5. No signing secrets are stored in the repo; the release build reuses the debug keystore. There are **no** release upload-keystore credentials configured — this is disclosed rather than hidden. To distribute a properly release-signed APK, add `keystore/` + secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) to GitHub and extend the workflow.

**Release steps**: bump `versionName`/`versionCode` in `app/build.gradle.kts` → push to `main` → `git tag v2.x && git push origin v2.x` → workflow attaches `KansoLedger-v2.x.apk` to the release.

## 10. Downloading & installing the APK

> **📌 Safe working v1**: the `v1.0.0` GitHub Release is marked **"SAFE WORKING v1"** — it is the verified, known-good baseline build (empty ledger, ৳0.00 defaults, BDT default, zero-network). If a newer build ever misbehaves, return to that release. Later prereleases (v1.1.0-beta*, v1.2.0-build*, v2.0.0-build*) build on top of it and are labeled pre-release.

1. Open the repository's **Releases** page.
2. Download `KansoLedger-<version>.apk` (or grab the Actions artifact for untested intermediate builds).
3. On an Android device, allow "Install unknown apps" for your browser/file manager and install.
4. First launch opens an **empty** ledger: balance ৳0.00, four zeroed accounts, no entries. Start with **ADD MONEY → Initial Balance**, then **RECORD EXPENSE**.
5. Smart SMS Tracking, notifications and the app lock each explain themselves before any system permission prompt — everything is opt-in.

## 11. Running tests

```bash
./gradlew test
```

**141 locally verified tests** (JDK 17 + kotlinc, same sources CI builds) covering: analytics totals, zero-budget truthfulness, report ranges & boundaries, the v2 report contract (fees separable yet debited, transfers excluded from income/expense, SMS-vs-manual origin counts, pending counted-but-never-money, refund breakout, reconciliation + account sections in CSV, transfer-fee balance agreement), refund single-counting, CSV totals reconciliation, category seed integrity, payment methods + legacy ids, keypad math, **SMS parsing on fictional fixtures only**, **ingest planning (dedup fingerprints, modes, balance-only, account resolution)**, **account state factory (fees, method fallback, transfers net-zero, pending isolation, reconciliation)**, recurring detection & forecasts, savings-goal projections, calendar day/week/month sums, insights inputs.

**Not covered (disclosed)**: Room migrations (`1→2`, `2→3`) execute on Android — no JVM harness exists for them here; PDF rendering and the Compose UI compile only in CI (`android.graphics.pdf` / Compose cannot be unit-tested on JVM); SMS inbox reading, broadcast delivery and BiometricPrompt are runtime behaviours that require a device.

## 12. Limitations (stated plainly)

- **Release APKs are signed with the debug keystore** — no Play upload key is configured; this is disclosed, not hidden.
- **Play policy**: publishing to Google Play requires declaring `READ_SMS` in Play Console (policy review). The feature is optional and off by default; sideloaded builds work the same.
- **Migration `2→3` is not JVM-tested** (no Room schema JSONs / instrumented tests in this repo) — create a backup before upgrading if your data matters.
- **App lock is not encryption** and not device security; a locked device with root access or extracted app files is out of scope.
- **SMS parsing is deterministic and extensible**, not omniscient: formats change; unknown or ambiguous messages land in review rather than being guessed.
- Notifications only appear if `POST_NOTIFICATIONS` is granted (Android 13+); SMS live detection requires the receiver permission and the feature switch.
- Money-in entries capture the day via a picker; the time is the save time (no time-of-day editing).
- Built-in default categories cannot be deleted (rename/reassign/archive by design).
- PDF output is exercised by the CI build only; CSV/logic paths are unit-tested.
- Node.js deprecation warnings in CI are from GitHub Actions tooling, not the project.

## 13. Contributing

- Keep the app **zero-network**, **single-database**, **no-demo**. No seed transactions, no placeholders, no remote calls.
- Currency is always internal minor units; format through `CurrencyFormatter`.
- Ledger/account/category mutations go through `SpendingRepository` so the audit trail and stable-id invariants hold.
- New SMS formats: extend `SmsParsingRules`/`SmsParser` with fictional fixtures + tests; never store raw message bodies anywhere.
- Pending detections and forecasts must never be added into confirmed totals.
- Prefer Material 3 + `LocalKansoColors` tokens over hard-coded hex values.

## 14. License & attributions

- Project code: Apache-2.0-compatible (AndroidX, Compose, Room, Material 3, Biometric are Apache-2.0).
- **Ubuntu typeface family** bundled in `res/font/` — **Ubuntu Font License (UFL v1.0)**.
- ৳ symbol rendering uses Android's system Bengali fallback fonts where the bundled Ubuntu family has no Bengali glyph.

---

*Crafted with a zero-cloud, editorial-ledger mindset. Keep your money on-device.*
