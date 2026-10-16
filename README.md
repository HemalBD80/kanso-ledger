# Kanso Ledger — 100% Local Android Spending Tracker

**Kanso Ledger** is a native Android personal spending tracker written in **Kotlin** and **Jetpack Compose**, engineered with **100% local on-device SQLite persistence (AndroidX Room)** and a tactile **Swiss editorial paper & Obsidian Ink** UI — nothing leaves your phone, and there is no demo/built-in ledger. Every screen is rebuilt from scratch so a fresh install starts clean.

---

## 1. Overview

- **Platform**: Android 7.0+ (minSdk 26), Kotlin, Jetpack Compose, Material 3.
- **Persistence**: 100% on-device Room + SQLite. No cloud, no network permission (`android.permission.INTERNET` is intentionally absent).
- **UI**: Swiss-layed editorial ledger aesthetic — deep espresso ink on warm paper (light) vs. obsidian dark (dark).
- **Data discipline**: all currency is stored in integer minor units (cents) and rendered with `fontFeatureSettings = "tnum"` so every figure aligns like a mechanical ledger.
- **Entry UI**: an oversized tactile 4×4 keypad in `TactileExpenseSheet.kt` with live inline math (`14.50 + 3.20` → `$17.70`), haptics, merchant auto-complete, category pills, payment method selector, and recurring toggle.
- **Forecasts**: `DailyRhythmChart.kt` is a custom `Canvas` daily spend chart with a dashed daily target, plus safe-to-spend-per-day and pacing status.

## 2. Features

- Log spending with inline math keypad + merchant auto-complete
- Category pills + search (merchant / note / category)
- Month navigation + category filter
- Monthly budget & per-category caps, with currency selector ($/€/£/₹/¥/₩)
- Local JSON backup & restore (copy to clipboard or paste a snapshot)
- Local-only vault (zero internet permission)
- Light / dark / system theme modes
- Weekly + monthly view with daily spend rhythm chart

## 3. Screenshots / Preview

No screenshots are bundled in the repo. A text sketch of the default (light) view:

```
┌────────────────────────────────────────────────────────────┐
│  KANSO • ON-DEVICE SQLITE VAULT    ◉ Theme  ◉ Budget  ◉  │
│  Personal Ledger            February 2026 STATEMENT        │
├────────────────────────────────────────────────────────────┤
│  TOTAL OUTFLOW                  ████████ 62% VS PACE     │
│  $ 0.00                       $1,800.00 MONTHLY CAP      │
│  ┌──────────────────────────────┴───────────────────────┐│
│  │  Safe / day $38.00  •  Daily avg $0.00               ││
│  └───────────────────────────────────────────────────────┘│
│  DAILY SPEND RHYTHM        DAILY TARGET  $60.00          │
│  ┌─ daily bar chart (canvas) ────────────────────────┐  │
│  └──────────────────────────────────────────────────────┘│
│  Search ...  [ALL] [DINING] [GROC] [TRN] [HSG] [SHP] [SUB]│
│  ┌──────────────────────────────────────────────────────┐│
│  │  NO ENTRIES YET  —  This month has no recorded spending. Use RECORD EXPENSE to add your first entry.  │
│  │  (tap + to begin)                                   ││
│  └──────────────────────────────────────────────────────┘│
│  FAB + RECORD EXPENSE                                  │
└────────────────────────────────────────────────────────────┘
```

## 4. Tech stack & requirements

- **Kotlin** 2.0.21 · **Compose Compiler** (kotlin-compose plugin) · **Compose BOM** 2024.11.00
- **Material 3** + material-icons-extended
- **AndroidX Room** 2.6.1 + KSP (database schema export enabled)
- **Android Gradle Plugin** 8.7.2 · **Gradle** 8.9
- **compileSdk 35** · **minSdk 26** · **targetSdk 35**
- **JDK 17** · **Android SDK 35 (platform + build-tools 35.0.0)**

## 5. Project structure

```text
/app/src/main/
├── AndroidManifest.xml          # zero network permission
├── java/com/kanso/spending/
│   ├── KansoApplication.kt
│   ├── MainActivity.kt
│   ├── data/
│   │   ├── model/  SpendingCategory.kt, PaymentMethod.kt
│   │   ├── local/
│   │   │   ├── entity/      ExpenseEntity, MonthlyBudgetEntity, CategoryBudgetEntity
│   │   │   ├── dao/         SpendingDao
│   │   │   ├── KansoDatabase.kt
│   │   │   └── LocalBackupManager.kt
│   │   └── repository/      SpendingRepository
│   ├── domain/
│   │   ├── CurrencyFormatter.kt
│   │   ├── KeypadExpressionEvaluator.kt
│   │   ├── SpendingAnalyticsModels.kt
│   │   ├── SpendingAnalyticsEngine.kt
│   │   └── SpendingAnalyticsEngineTest.kt
│   └── ui/
│       ├── SpendingViewModel.kt
│       ├── theme/             Color.kt, Type.kt, Theme.kt
│       ├── components/
│       │   ├── EditorialHeaderBar.kt
│       │   ├── HeroLedgerCard.kt
│       │   ├── DailyRhythmChart.kt
│       │   ├── TransactionLedgerItem.kt
│       │   ├── TactileExpenseSheet.kt
│       │   ├── BudgetPlannerSheet.kt
│       │   └── LocalVaultSheet.kt
│       └── screens/           SpendingDashboardScreen.kt
└── res/
    ├── font/                  # Ubuntu typeface family (Regular, Bold, Italic, BoldItalic)
    ├── values/                colors.xml, strings.xml, themes.xml
    ├── mipmap-anydpi-v26/     ic_launcher.xml, ic_launcher_round.xml
    └── drawable/              ic_launcher_foreground.xml, ic_launcher_background.xml

/root
├── settings.gradle.kts · build.gradle.kts · gradle.properties · gradle/libs.versions.toml · gradlew
```

> Note: `SampleLedgerSeeder` and all demo/sample data machinery have been removed from `data/repository/`. A fresh install always starts with an empty ledger.

## 6. UI / Theme

### 6.1 Color palette (
`ui/theme/Color.kt`
)

- **Paper (light)**: Alabaster Paper `#F6F3EC` → Ivory Surface `#FFFDF9` → Sandstone Elevated `#EEEAE0` → Hairline Stone `#E2DDD1` → Deep Espresso Ink `#161614`.
- **Obsidian (dark)**: Obsidian Background `#111210` → Basalt Surface `#191B18` → Graphite Hairlines `#2D302A` → Warm Chalk `#F2EFE9`.
- **Accents**: Botanical Pine `#1E4D3B`, Terracotta Vermilion `#C84B31`, Fjord Slate `#2B5876`, Ochre `#D9822B`, Coral Ember `#E56B51`.

### 6.2 Typography — Kanso (
`ui/theme/Type.kt`
)

Every style uses **tabular numerals** (`tnum`) for ledger alignment. The metrics:

| Role | Weight | Family | Notes |
|------|--------|--------|-------|
| displayLarge / displayMedium | Bold | Ubuntu | Key figures / dashboard large readouts |
| headlineMedium | Bold | Ubuntu | Section titles (e.g. "Personal Ledger") |
| titleLarge / titleMedium | Bold | Ubuntu | Card titles, buttons |
| bodyLarge / bodyMedium / bodySmall | Regular / Regular+Italic | Ubuntu | Body copy; bodySmall is italic emphasis |
| labelLarge / labelMedium / labelSmall | Bold | Ubuntu | Labels, chips, captions |

### 6.3 Ubuntu typeface family (bundled in `res/font/`)

Four UFL-licensed Ubuntu fonts are bundled and wired into **both** the Compose theme and the XML window theme:

| File | Weight | Style | Applied to |
|------|--------|-------|------------|
| `ubuntu_regular.ttf` | 400 | normal | Body text, input text, captions |
| `ubuntu_bold.ttf` | 700 | normal | Headings, labels, key figures |
| `ubuntu_italic.ttf` | 400 | italic | Emphasis / annotations |
| `ubuntu_bold_italic.ttf` | 700 | italic | Bold+emphasis runs |

- `res/font/ubuntu_font_family.xml` defines the `<font-family>` linking all four.
- `ui/theme/Type.kt` maps the four cuts via `Font(R.font.…)` + `FontWeight`/`FontStyle`; no weight silently falls back to a system font.
- `res/values/themes.xml` sets `android:fontFamily = @font/ubuntu_font_family` on the base window theme for XML-inflated dialogs/popups.

### 6.4 Light & dark modes

`KansoSpendingTheme` selects a paper-light or obsidian-dark color scheme (plus a "system" fallback) and supplies `LocalKansoColors`, which components read so accent/border/ink stay correct in both modes.

## 7. Architecture

- **MVVM + single `SpendingViewModel`** owns `SpendingDashboardUiState`; UI is stateless and recomputes from the Room database.
- **Room** (`KansoDatabase`): `expenses`, `monthly_budgets`, `category_budgets` tables. Every read is a `Flow` rebound to the UI.
- **Zero network**: no `INTERNET` permission. Backup is a JSON snapshot (export/clone/restore) stored by the user, not by the app.
- **Data flow**: ViewModel → repository (Room) → UI state → composables. There is intentionally no pre-seeded or demo ledger.

## 8. Data layer & backup/restore

- **Schema** (`ExpenseEntity`, `MonthlyBudgetEntity`, `CategoryBudgetEntity`) is exported via Room's `room.schemaLocation`.
- **Backup**: `LocalBackupManager` serializes a `LedgerSnapshot` (expenses, budgets, currency symbol) to a pretty-printed JSON snippet you can copy or paste back in.
- **Restore**: `importLocalBackupJson` replaces the entire local vault from a snapshot.
- **Wipe**: `wipeAllLocalData` clears all three tables + budgets (and the downloaded backup preview).

## 9. Building from source

1. **JDK 17** (`JAVA_HOME` → your JDK 17 build).
2. **Android SDK** with `platforms;android-35` and `build-tools;35.0.0` (set `ANDROID_HOME` / `sdk.dir` → `local.properties`).
3. `./gradlew assembleRelease` from the repo root.

> **Local host note**: this workspace's build host is `aarch64` Linux, while Google's SDK `aapt2`/`d8`/`r8`/`apksigner` launchers ship as `x86-64` only, so a native Android APK cannot be assembled locally here. To produce a genuine release APK, use the GitHub Actions CI defined in [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) (Ubuntu x86-64 runner). See **10. Releasing** for the workflow and **11. apk-test** below for the download-and-test path.

## 10. Releasing to GitHub (APK)

The repository ships a self-contained CI workflow that builds and publishes the release APK:

`.github/workflows/build-apk.yml`

- **Merge build** (on `main` push and tags `v*`, or `workflow_dispatch`):
  - `./gradlew assembleRelease` → APK at `app/build/outputs/apk/release/*.apk`.
  - APK uploaded as the Actions artifact `kanso-ledger-apk`.
- **Publish** (on tag push or `workflow_dispatch`):
  - Determines the release tag: for a tag push it uses the tag name; otherwise `v${versionName}-build.${GITHUB_RUN_NUMBER}` (since `versionName = "1.0.0"`, a dispatch release is `v1.0.0-build.N`).
  - Downloads the APK artifact, stamps it as `KansoLedger-<tag>.apk`, and creates a GitHub Release with that APK attached (1 asset).

**Release steps (a release tag follows this path):**

1. Update `versionName` (and optionally `versionCode`) in `app/build.gradle.kts`.
2. Commit and push to `main`.
3. Create + push an annotated tag, e.g. `git tag v1.0.0 && git push origin v1.0.0`.
4. Wait for the `Build & Publish APK` workflow run to complete.
5. Download the `KansoLedger-<tag>.apk` from the **Releases** page — that APK is your signed, release-ready build.

## 11. apk-test folder (download & test before release)

During development, the built APK is staged to the repository root in an `apk-test/` folder so you can grab it and test without publishing:

```
apk-test/KansoLedger-v1.0.0.apk
```

Place a build artifact here, download it, install, and test. Before a formal release, sanity-check:

- The checksum of `apk-test/*.apk` matches the GitHub Release asset.
- The APK installs cleanly and the vault is empty on first open.

The `apk-test/` directory is **not** tracked in git — add it to `.gitignore` if you prefer to keep it scratch-only.

## 12. Running tests

```bash
./gradlew test          # local JVM unit tests
```

The project ships focused tests in `app/src/test/java/com/kanso/spending/domain/`:

- `KeypadExpressionEvaluatorTest.kt` — decimal/integer → cents, inline `+`/`-`, duplicate-decimal prevention.
- `SpendingAnalyticsEngineTest.kt` — monthly totals, remaining budget, pacing status, day sections, category breakdown.

## 13. Rebuilding fresh (no demo/sample content)

The app ships **no demo or sample ledger**. There is no seed data and no "load demo" entry point anywhere. Key points if you rebuild the app:

- Never re-introduce a `SampleLedgerSeeder` and never auto-insert mock rows on first launch.
- The dashboard's initial state is a genuinely empty `MonthlyLedgerAnalytics` (0.00 spent, empty day sections, clear italic prompt for the user to add their first transaction).
- Any placeholder or dummy text living in a fragment/screen must be removed; every UI string is either user-entered or derived from the local database.

## 14. Contributing

- Keep the app **zero-network**, **single-database**, and **no-demo**. Add no seeded data, no placeholder strings, and no external network calls.
- UI text uses semantic labels with clear visual hierarchy (headline for titles, bold for figures, italic for emphasis).
- Currency is always internal cents; use `CurrencyFormatter` for display.
- Prefer Material 3 + `LocalKansoColors` instead of hard-coded hex values.
- Bundle a new font by dropping the `.ttf` into `app/src/main/res/font/` and registering it in `kanso_font_family.xml`.

## 15. License & attributions

- **Kanso Ledger** — Apache-2.0-compatible Android project (AndroidX, Compose, Room, Material 3 are Apache-2.0).
- **Ubuntu typeface family** (Regular, Bold, Italic, BoldItalic) bundled in `res/font/` — **Ubuntu Font License (UFL v1.0)**, from the Ubuntu Font Family project. Font files remain under the terms of the UFL.

---

*Crafted with a zero-cloud, editorial-ledger mindset. Keep your money on-device.*
