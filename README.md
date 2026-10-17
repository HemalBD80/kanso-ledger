# Kanso Ledger — 100% Local Android Money Tracker (BDT)

**Kanso Ledger** is a native Android personal finance tracker written in **Kotlin** and **Jetpack Compose**, engineered with **100% local on-device SQLite persistence (AndroidX Room)** and a tactile **Swiss editorial paper & Obsidian Ink** UI. Every transaction, balance, forecast and report is calculated exclusively on the phone. The ledger, manual tracking, local SMS parsing and all calculations are fully offline; the only network code in the app is an **optional, off-by-default AI-assisted SMS parsing feature** (see §2.11) that you must explicitly enable, consent to, and configure before anything can be sent. No demo data, no ads, no subscriptions, no analytics.

Currency is **Bangladeshi Taka (৳)** by default and everything starts at **৳0.00** on a fresh install so testing always begins from a clean, empty ledger.

**v2.0 — Personal Finance Intelligence**: optional on-device **Smart SMS Tracking**, a **multi-account ledger** with transfers and provider reconciliation, a financial **calendar**, **recurring payments** with labelled forecasts, **savings goals**, **quick entry**, an optional **biometric app lock**, configurable **notifications**, and reports that separate confirmed facts from pending review.

**v2.4 — AI-Driven SMS Understanding**: the optional AI provider (§2.11) is now the **primary interpreter** for messages your local filter reads as *possibly financial*, so the many real Bangladeshi bank / wallet / recharge / bill formats work without a hardcoded parser per template — while obvious OTPs, promos and unrelated texts are still dropped **on-device** and never sent. Financial integrity is enforced locally: **cashback is incoming money (not a refund)**, **reversals are never new income**, **own-account transfers are neither income nor expense**, **fees are recorded once**, a **balance-only notice never creates an expense**, reported balances stay separate from calculated ones, references are kept as **strings** (leading zeros preserved), and the model **cannot** write SQL, touch the database, or delete a transaction. A confident result auto-saves only when you have explicitly chosen **Auto**; everything uncertain waits in review.

**v2.4.1 — fix: "connected but nothing imports"** (see §2.11). Three defects conspired to keep the ledger at ৳0.00 even though the connection test passed: (1) the local filter's *non-financial* verdict was **terminal** — the AI was never consulted — and it fired for **any** message lacking the literal token `BDT`/`Tk`/`taka`, silently dropping real bank SMS; (2) the reply parser applied its size guard to the whole HTTP envelope, so a large reasoning trace around a valid small answer was thrown away; (3) the message identity used a **parse-dependent** fingerprint, so a re-import after enabling AI did not recognise (or upgrade) previously-queued messages. All three are fixed: genuine non-financial text is still dropped on-device, everything with a financial signal that the local rules can't read is kept and offered to the AI (or queued for review), the parser reads a small answer inside a large envelope, and identity is the **deterministic local fingerprint** so reprocessing is **idempotent** and a still-pending message can be **reclassified** on re-import — never blindly imported. A new **Test classification** diagnostic sends one *fictional, labelled* bank SMS through the full classify pipeline to prove the model can actually classify this domain (not just answer a ping).

---

## 1. Overview

- **Platform**: Android 8.0+ (minSdk 26), Kotlin 2.0.21, Jetpack Compose, Material 3
- **Persistence**: 100% on-device Room + SQLite. No cloud storage, no analytics. `INTERNET` is present **only** for the optional AI feature, which is off by default and gated by user consent (see §2.11)
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
| `INTERNET` | **Only** the optional AI-assisted parsing feature (§2.11) — off by default, consent + credential required. With AI off, nothing is ever sent | No — every feature works fully with AI off |

**Google Play policy note**: apps that use `READ_SMS` must declare it in Play Console and pass policy review. In Kanso Ledger the SMS feature is optional, off by default, reads only to parse on-device, and is fully replaceable by manual paste or the Android share sheet.

## 2. Features

### 2.1 Smart SMS Tracking (optional, on-device)
- **First-launch Smart Setup**: a one-time onboarding screen explains the feature and its privacy implications, then offers exactly two choices — *Enable Smart SMS Tracking* or *Continue without SMS access*. Permissions (`READ_SMS` for history, `RECEIVE_SMS` for incoming messages, `POST_NOTIFICATIONS` optional) are requested only from an explicit tap, never silently. Denial, permanent denial and later revocation are handled with plain-language guidance and the documented fallbacks (paste/share + full manual tracking); revoking access while the feature is on pauses message reading and says so — your records are untouched
- Off by default; enabling shows **why** access is needed before the system prompt
- Deterministic, hand-written parsers for **bKash, Nagad, Rocket, banks and cards** — pure Kotlin, no cloud ML, no model downloads, extensible rule tables
- Recognizes money sent/received, card & ATM activity, wallet/bank transfers, **mobile recharge & internet packages**, **utility and other bill payments**, refunds/reversals, fees, and balance notices (which never become transactions)
- Extracts: transaction type, amount, fee, datetime, reference id, provider, account hint, merchant/recipient, reported balance
- **Two import modes**: *Review before save* (everything waits) and *Auto* (only high-confidence transactions save immediately). Ambiguous messages **always** wait for review — the app never guesses
- **Preview-first history import**: a dry run reports confident / needs-review / duplicate / ignored / balance-notice counts plus parsed samples **before anything is written**, then you explicitly choose *record the confident ones now* or *review everything first* — one-shot per import, your saved default mode is not rewritten. Duplicates of what you already have are listed for inspection (provider, amount, reference)
- Review queue: confirm, edit amount, pick category/account/transfer endpoints, **save-as-rule** for the merchant, or reject. Rejection records nothing
- **Deduplication**: fingerprint = SHA-256 of `provider|ref|REF` when a reference id exists, otherwise `provider|sender|amount|type|timestamp/1000`. Identical amounts alone are never treated as the same transaction — redelivered broadcasts and history re-imports cannot double-record
- Date-filtered **historical import**; **undo last import** (removes the batch and recalculates balances); **clear SMS import history** (erases metadata + review queue, never your recorded transactions)
- **Paste** a single message or **share** text into the app from any app — zero SMS permission needed
- Dashboard status line: tracking **ON/OFF**, processing mode, last successful run, pending count — and the Smart SMS sheet covers review, correction, undo, mode changes and disabling
- Incoming processing is **permission-gated and failure-contained**: a database error during a broadcast never crashes the app, and idempotent fingerprints make the redelivery safe
- Privacy: message bodies live in memory only during parsing — **never stored, logged, notified, backed up or crash-reported**. Only structured fields and fingerprints reach the database. All parsing, categorization, deduplication and calculations run on-device. AI assistance is **optional, off by default** and never required (§2.11)
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
- Saved through Android's Storage Access Framework — generated on-device, using no network

### 2.11 AI-assisted SMS understanding (optional, OFF by default)

An **optional** assistant that interprets SMS your deterministic local parser reads as **possibly financial**. It is the *only* network code in the app and it is off until you deliberately turn it on. Everything else — the ledger, manual tracking, local SMS parsing, import, dedup, all calculations — works fully offline and is unaffected whether AI is on or off.

**What it does, step by step (historical import and live messages):**
1. The **local** parser classifies every message first. Only genuinely non-financial messages are dropped here and **never sent** — obvious OTPs, marketing without a transaction reference, and unrelated personal text. Any message with a **financial signal** the rules can't fully read (a currency mention, a transaction reference, or an amount paired with a transaction verb) is kept and becomes eligible for the AI — or, with AI off, queued for your review. The inbox as a whole is never uploaded.
2. Before anything leaves the device, **identifiers are stripped** (`AiPromptBuilder.redact`): phone numbers, emails, card-like numbers and long digit runs are removed; masked hints like `***558` and the financial core (amounts, references) are kept.
3. The redacted text is sent to the configured model via an OpenAI-compatible `POST /chat/completions` with `Authorization: Bearer <key>`. You choose the **Model ID**, **base URL** and key; the shipped default targets GLM 5.3 Flash (`z-ai/glm-5.3-flash`) on the NVIDIA endpoint.
4. The model must return a **strict JSON object** with the v2.4 fields: `is_financial`, `provider`, `transaction_type`, `amount_bdt`, `fee_bdt`, `reported_balance_bdt`, `account_hint`, `merchant_or_recipient`, `transaction_reference`, `transaction_timestamp`, `category_suggestion`, `payment_method_suggestion`, `confidence`, `evidence`, `warnings`. The SMS is framed as **untrusted data, never instructions** — text inside a message can never change the app's behaviour.
5. **Every response is validated locally** (`AiResponseValidator`): amounts must parse and be positive, only **BDT** is accepted, a missing amount/type is **never guessed**, an unknown `transaction_type` is **rejected**, and any doubt, low confidence or reversal is **queued for review**. The AI **cannot** execute database operations, write SQL, delete rows or write balances — a reported balance stays advisory.
6. A message's **identity** is the **deterministic local fingerprint** (`SmsParser.fingerprint()`), computed from the sender, reference, amount, type and timestamp — **not** the model's. This makes reprocessing **idempotent**: a repeated broadcast, a re-import or a restart dedups to **one** record, and a message first queued while the AI was off can be **reclassified and upgraded** on a later import instead of being double-recorded or blindly imported.
7. Confident results follow your chosen tracking mode (Review vs Auto); ambiguous ones always wait. A summary of recorded / ignored / duplicate / pending is shown, and you can **undo an import** or **clear SMS processing metadata** (recorded ledger rows are never touched).
8. Successful interpretations are **cached locally** (a bounded, hashed, on-device reply cache) so an identical redacted message is not sent twice; a cache miss simply calls the provider. A transport failure degrades to local parsing — it never blocks, drops or guesses a transaction.

**Financial-integrity rules baked into the local mapping (the AI cannot override them):**
- **Expense** (debit / payment / purchase / recharge / bill) is recorded as an expense only when the message supports it.
- **Cashback / reward** is **incoming money**, not a refund and not an expense reduction, and is tagged with its own money-in source.
- **Refunds** map to money-in from `MoneySource.REFUND`; **reversals** are represented explicitly and **queued** so they can be matched to the original, never counted as new income.
- **Own-account transfers** (and cash withdrawal/deposit) are **neither income nor expense**; **fees** are recorded once, not double-counted inside a payment.
- **Balance-only** notices never create an expense; a reported balance is stored **separately** from the calculated one and never overwrites it.
- Missing amounts, opening balances and categories are **never invented**; an unknown category stays uncategorised rather than being guessed.

**Setup:**
1. Open **SMART SETTINGS → AI-ASSISTED PARSING**.
2. Toggle **Enable AI-assisted parsing**, tick **I consent to external processing**.
3. Enter your **Model ID** (`z-ai/glm-5.3-flash`), **API base URL** (`https://integrate.api.nvidia.com/v1`) and **API key**, then **Save**.
4. Three gates must all be open or nothing is sent: *feature on* + *consent* + *credential present*. Turn any off and the app instantly reverts to 100% local parsing.

**Credential security (stated honestly):**
- The API key is entered **at runtime by you** and stored in the app's **private SharedPreferences** (`kanso_ai_prefs`). It is **never hardcoded, never committed to Git, and never baked into the APK** — the build contains no secret.
- **Risk of a client-held key:** any key present on a device can be extracted from a rooted device or a decrypted APK backup. This project is a purely client-side (serverless) app, so this residual risk is disclosed rather than hidden. A **server-side proxy** that holds the key as an environment secret is the only way to keep a key truly private; that is the recommended production setup. Do not commit keys to this repository — add them to `.gitignore` and rotate any key that has ever been shared.

**External data-sharing disclosure:**
- With AI **off** (the default), **no data leaves the device** — identical to the app before this feature existed.
- With AI **on**, only the **redacted** text of messages your local filter read as *possibly financial* is transmitted to the AI provider. Your whole inbox is never uploaded, stored message bodies are never sent, and no ledger data, balances you calculated, or analytics leave the phone.
- The third-party provider (NVIDIA/Zhipu) sees whatever is sent and is subject to its own privacy policy.

**Where the AI is wired in (v2.4.0):** beyond message interpretation, the same gated, validated client also feeds three features — each keeps local logic authoritative and treats the AI as an optional, lowest-priority hint that fails closed:
- **Historical import & live parsing** (already covered above) — first-launch import, date-ranged import, live SMS and paste all route through the same gated path.
- **Transaction categorisation** — the model's `category_suggestion` is used **only** when neither a user rule nor a bundled keyword matched, and is mapped through `AiCategoryMapper` to a **real bundled category id**. The model cannot invent a new category and cannot force a transaction into one (a `transfer`/`salary`/unknown suggestion is discarded → "other"). A user rule or keyword always wins. "Mobile & Internet" resolves to the ledger's **Bills & Utilities** bucket.
- **Payment method (display hint)** — a `payment_method_suggestion` is mapped through `AiPaymentMethodMapper` to a real method and used **only** when the resolved account does not already determine it. A known account is always authoritative; an unresolvable suggestion is discarded.
- **Optional financial insights** — an on-demand AI *narrative* over the month's **locally-computed** figures (`AiInsightsNarrator`). Only aggregate totals and category labels are sent — never SMS text or identifiers. The numbers themselves are never computed or altered by the AI; it only describes them. Failure returns no narrative and leaves the local observations untouched.
- **Connection diagnostic** — **Test connection** in settings sends **one trivial "OK" ping** (no SMS, no ledger data) and reports the typed outcome (success + latency, or the specific auth/rate-limit/HTTP/network failure). It never displays the key.
- **Classification diagnostic (v2.4.1)** — **Test classification** sends one **fictional, clearly-labelled** bank SMS (no real message, account or balance) through the *same* interpret → parse → validate pipeline used for imports, and reports whether a valid structured classification came back. This separates "the endpoint answers a ping" from "the model can actually classify this domain", so a wrong Model ID, a non-JSON reply or a truncated answer is surfaced instead of silently yielding zero imports. No credential or message body is ever shown.

**Known limitations (§12) — see also:** the on-device path (Android networking, consent UI, real inbox SMS) must be verified on a device. A rate-limited, offline, mis-configured or failing AI **never blocks, drops or guesses a transaction** — it degrades to the local parser. The default model is a **reasoning model**: it emits a hidden reasoning trace before its answer, so requests reserve a generous token budget (4096) — a truncated reply (`finish_reason: "length"`) or a null answer is discarded and falls back to local parsing rather than being trusted. The endpoint occasionally returns a **transient 401** that succeeds on immediate retry; the client retries a bounded number of times and only reports a credential error if it persists (a genuinely bad key returns 403). The live API was used to verify auth, request format, and response schema, and the response-shape tests are pinned against replies captured from it.

## 3. Screens / workflows

```
First launch: SmartOnboardingScreen (shown once) — welcome & privacy → two explicit
        choices (enable Smart SMS / continue without SMS) → permission taps (READ_SMS,
        then RECEIVE_SMS, then optional POST_NOTIFICATIONS — requested strictly one at a
        time via a state-driven launcher, never re-prompted after a denial; permanent
        denial offers "Open Android Settings"; returning from Settings refreshes state) → initial
        history import with preview + duplicate inspection + auto-vs-review choice
Dashboard
├── Balance strip (available balance = net funds, money in • month, expenses • month)
├── Intelligence: NET FUNDS • ALL ACCOUNTS (+ pending badge, reconciliation warning,
│        smart status: ON/OFF • mode • last run • pending) + observations
├── Action rows: ADD MONEY • MANAGE CATEGORIES • DOWNLOAD REPORT
│                SMART SMS • ACCOUNTS • CALENDAR • RECURRING • GOALS • QUICK ENTRY • SMART SETTINGS
├── Hero ledger card + daily spend rhythm chart
├── Filters: type • category • payment method • search
├── Payment-method-wise spending card
└── Day-grouped ledger (editable)
Sheets: TactileExpenseSheet • MoneyInSheet • CategoryManagerSheet • ReportSheet • BudgetPlannerSheet
        • LocalVaultSheet • SmartSmsSheet • AccountsSheet • CalendarSheet • RecurringSheet
        • GoalsSheet • QuickEntrySheet • SmartSettingsSheet
        (SmartSmsSheet & onboarding share HistoryImportSection: date-ranged preview-first import)
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
│   ├── sms/             SmsModels, SmsParsingRules, SmsParser, SmsIngestPlanner (dedup/mode routing),
│   │                    SmsAccessPolicy (feature/permission gates for receiver + UI)
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
    │                RecurringSheet, GoalsSheet, QuickEntrySheet, SmartSettingsSheet,
    │                HistoryImportSection (preview-first import, shared),
    │                SmartOnboardingScreen (first-launch Smart Setup)
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
- Refunds are money-in (`MoneySource.REFUND`) — counted once; they never reduce expenses. **Cashback/rewards** are money-in too (`MoneySource.CASHBACK`), never relabelled a refund. **Reversals** are queued for review and linked to their original, never counted as new income. **Own-account transfers** (and cash withdrawal/deposit) are neither income nor expense.
- Renaming a category changes only the display name; stable ids keep history valid. Audit entries are written on every ledger/category/account mutation and on vault wipe/restore.

## 7. Local database & migration

- Room version **4**. `MIGRATION_1_2` is byte-identical to the released v1.x migration. `MIGRATION_2_3` is **non-destructive**: adds accounts/transfers/adjustments/sms_meta/sms_detections/recurring/savings/goals/contributions/category_rules, backfills legacy rows to structural accounts (`wallet→acc_wallet`, `debit`/`credit`/`card→acc_card`, `transfer→acc_bank`, else→acc_cash), and seeds the four default accounts a fresh install also gets. `MIGRATION_3_4` is **non-destructive**: adds one advisory column, `sms_detections.payment_method_hint` (AI display hint; defaults `""` and never overrides a known account).
- **Backup schema v3** adds accounts, transfers, adjustments, recurring rules, goals, contributions, rules, sms metadata and detections. **No message bodies are ever in a backup.** v1 and v2 backups still restore (missing structures default, never invented).
- **Wipe** clears every table and re-seeds default categories + accounts (never leaves a broken picker).

## 8. Building from source

1. **JDK 17**
2. **Android SDK** with `platforms;android-35`, `build-tools;35.0.0`
3. `./gradlew test` — run the unit tests
4. `./gradlew assembleRelease` — produce the APK at `app/build/outputs/apk/release/`

> **Local host note**: this workspace is aarch64 Linux while Google's `aapt2`/`d8`/`r8` launchers ship x86-64 only, so builds happen in CI. Use the GitHub Actions workflow below. The pure Kotlin domain + data layer compiles and tests locally (JDK 17 + kotlinc, 259 tests); the Compose/Room-processor layer is verified by CI.

## 9. GitHub Actions build & release

`.github/workflows/build-apk.yml` (single workflow, no duplicates):

1. `./gradlew test` — unit tests run **before** any packaging; failures fail the job loudly ("Build & Publish APK").
2. `./gradlew assembleRelease` with JDK 17 on an `ubuntu-latest` runner.
3. APK uploaded as artifact `kanso-ledger-apk` for direct device testing.
4. **Publish** (tag push `v*` or manual dispatch): creates a GitHub Release with a versioned asset (`KansoLedger-<tag>.apk`); non-tag dispatches are marked **pre-release** (beta channel).
5. No signing secrets are stored in the repo; the release build reuses the debug keystore. There are **no** release upload-keystore credentials configured — this is disclosed rather than hidden. To distribute a properly release-signed APK, add `keystore/` + secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) to GitHub and extend the workflow.

**Release steps**: bump `versionName`/`versionCode` in `app/build.gradle.kts` → push to `main` → `git tag v2.x && git push origin v2.x` → workflow attaches `KansoLedger-v2.x.apk` to the release.

## 10. Downloading & installing the APK

> **📌 Safe working v1**: the `v1.0.0` GitHub Release is marked **"SAFE WORKING v1"** — it is the verified, known-good baseline build (empty ledger, ৳0.00 defaults, BDT default, fully offline with no `INTERNET` permission). If a newer build ever misbehaves, return to that release. Later prereleases (v1.1.0-beta*, v1.2.0-build*, v2.0.0-build*) build on top of it and are labeled pre-release.

> **📌 Safe working v2**: the `v2.0.0` GitHub Release is marked **"SAFE WORKING V2"** — the designated working v2 baseline: full v2.0 feature set (Smart SMS Tracking, multi-account ledger with transfers & reconciliation, calendar, recurring, goals, quick entry, app lock, notifications, extended reports), 141 unit tests green locally and in CI, release APK built by GitHub Actions. This is the release to track and pin for v2: if any newer build misbehaves, return here to bisect regressions against it. What was verified is exactly what tests + CI can verify — on-device paths (SMS reading, biometric prompt, notifications, migration 1/2→3, PDF rendering) are covered by the limitations list in §12, not by untested claims. Earlier `v2.0.0-build*` prereleases remain on the Releases page as build history.

1. Open the repository's **Releases** page.
2. Download `KansoLedger-<version>.apk` (or grab the Actions artifact for untested intermediate builds).
3. On an Android device, allow "Install unknown apps" for your browser/file manager and install.
4. First launch opens an **empty** ledger: balance ৳0.00, four zeroed accounts, no entries. Start with **ADD MONEY → Initial Balance**, then **RECORD EXPENSE**.
5. Smart SMS Tracking, notifications and the app lock each explain themselves before any system permission prompt — everything is opt-in.

## 11. Running tests

```bash
./gradlew test
```

**288 locally verified tests** (JDK 17 + kotlinc, same sources CI builds) covering: analytics totals, zero-budget truthfulness, report ranges & boundaries, the v2 report contract (fees separable yet debited, transfers excluded from income/expense, SMS-vs-manual origin counts, pending counted-but-never-money, refund breakout, reconciliation + account sections in CSV, transfer-fee balance agreement), refund single-counting, CSV totals reconciliation, category seed integrity, payment methods + legacy ids, keypad math, **SMS parsing on fictional fixtures only** (incl. recharge, utility bills, data packages), **ingest planning (dedup fingerprints, modes, balance-only, account resolution, historical re-import idempotency in both modes, duplicate notices)**, **SMS access policy (feature off / permission denied / revoked → processing gated, records untouched; setup chain asks each permission once and never re-prompts a denial; permanent denial detected for the Settings path)**, **account state factory (fees, method fallback, transfers net-zero, pending isolation, reconciliation)**, recurring detection & forecasts, savings-goal projections, calendar day/week/month sums, insights inputs, **AI integration (transport/HTTP mocked; response-shape tests pinned against replies captured from the LIVE endpoint): redaction removes identifiers before sending & never transmits the key, strict response validation (foreign currency rejected, missing amount never guessed, malformed/oversized/null-content replies never crash, confidence normalised from the model's real 0–1 scale), the full v2.4 vocabulary (expense/debit→expense, income/credit→income, cashback→income-not-refund, refund→income, reversal→review, transfer/cash_withdrawal/cash_deposit→transfer-never-income/expense, fee→fee, balance_notification→ignore), payment-method hint mapping, evidence preserved for the reviewer, prompt-injection resistance (SMS framed as untrusted data; hostile text can never change a validated result), AI+local fingerprint parity for cross-path dedup, interpreter fail-closed on every transport error; AI routing (non-financial messages filtered locally and never sent, only ambiguity reaches AI, every AI failure degrades to the local result, disabled AI is byte-for-byte offline behaviour); plus real-GLM behaviour (provider:null→sender fallback, "ATM withdrawal"/"send_money"/"balance_inquiry" type mapping, direction "in"); plus the wiring added in v2.3.0 — the connection probe (typed outcome per transport failure, never leaks the key, sends only a trivial "OK" ping), the AI→category mapper (resolves only to a real bundled id, lowest-priority behind user rules & keywords, rejects transfer/salary/unknown so the model cannot invent or force a category), and the AI insight narrator (returns null on every failure, sends only aggregate totals never SMS, strips markdown fences and length-caps).

**v2.4.1 additions (29 tests, in the same JVM harness):** local-eligibility regression (a currencyless bank debit / a bare `৳` amount / an amount+verb are **kept**, while an amount with no currency or verb, an OTP, a promo and personal chatter are still **dropped**), the **idempotent re-import upgrade** (a still-pending message is upgraded when a confident result now exists; a recorded one is never re-recorded; review mode never upgrades; an AI-resolved ambiguity upgrades via the deterministic local identity even though the model fingerprint differs), both modes over confident fixtures, plus diagnostics (every transport failure maps to a specific category, an HTTP 400/404/422 hints at a possibly-invalid Model ID, malformed vs validator-rejected are distinct, failure kinds aggregate) and parser robustness (a small valid answer inside a >36 KB reasoning envelope parses; array-style `content` parses; blank/`null`/empty-array content still fails closed; the **classification probe** reports success / non-financial / failure / not-configured and sends only the fictional fixture).

**Not covered (disclosed)**: Room migrations (`1→2`, `2→3`, `3→4`) execute on Android — no JVM harness exists for them here; PDF rendering and the Compose UI compile only in CI (`android.graphics.pdf` / Compose cannot be unit-tested on JVM); SMS inbox reading, broadcast delivery and BiometricPrompt are runtime behaviours that require a device. The **live AI round-trip** was verified at the **API level** (real `chat/completions` calls confirmed auth, request format, and response schema; the response-shape tests are pinned against replies captured from the live endpoint), but the **on-device path** — Android networking, the consent UI, and parsing real inbox SMS — still requires a device to confirm.

## 12. Limitations (stated plainly)

- **Release APKs are signed with the debug keystore** — no Play upload key is configured; this is disclosed, not hidden.
- **Play policy**: publishing to Google Play requires declaring `READ_SMS` in Play Console (policy review). The feature is optional and off by default; sideloaded builds work the same.
- **Migration `2→3` and `3→4` are not JVM-tested** (no Room schema JSONs / instrumented tests in this repo) — create a backup before upgrading if your data matters.
- **App lock is not encryption** and not device security; a locked device with root access or extracted app files is out of scope.
- **SMS parsing is deterministic and extensible**, not omniscient: formats change; unknown or ambiguous messages land in review rather than being guessed.
- Notifications only appear if `POST_NOTIFICATIONS` is granted (Android 13+); SMS live detection requires the receiver permission and the feature switch.
- Money-in entries capture the day via a picker; the time is the save time (no time-of-day editing).
- Built-in default categories cannot be deleted (rename/reassign/archive by design).
- PDF output is exercised by the CI build only; CSV/logic paths are unit-tested.
- Node.js deprecation warnings in CI are from GitHub Actions tooling, not the project.
- **AI-assisted parsing (§2.11) is optional and off by default.** When on it is the primary interpreter for messages the **local** filter reads as possibly financial — obvious OTPs/promos/unrelated texts are still dropped on-device and never sent; a message with a financial signal the local rules cannot fully read is **kept** (queued for review, or offered to the AI) rather than silently dropped, and a message first queued while AI was off is **reclassified idempotently** on a later import. The API format/auth/response-shape were verified against the live endpoint (tests pinned to real captured replies), but the on-device path needs a device; a client-held API key is extractable from a rooted device (a server-side proxy is the only way to fully hide it); the AI never writes SQL, deletes rows or overwrites calculated balances and any failure degrades to local parsing. Cashback is incoming money (not a refund), reversals are never new income, own-account transfers are neither income nor expense, and a reasoning-model truncated or null answer is discarded, never trusted.

## 13. Contributing

- Keep the **ledger itself offline-first**: no analytics, no remote calls in the accounting path. The only permitted network use is the optional AI feature behind its three gates (feature + consent + credential) — never add a network dependency to core ledger, balance or report logic.
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
