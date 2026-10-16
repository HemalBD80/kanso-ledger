# Kanso Ledger — 100% Local Android Spending Tracker

**Kanso Ledger** is a native Android personal spending tracker written in **Kotlin** and **Jetpack Compose**, engineered with **100% local on-device SQLite persistence (AndroidX Room)** and an authentic **Swiss Editorial Paper & Obsidian Ink** UI/UX inspired by curated Dribbble and Pinterest finance design studies (such as *Dime*, *Copilot Money*, *Spendee*, and *Teenage Engineering* tactile interfaces).

---

## 1. Anti-AI-Slop UI/UX Philosophy (Dribbble & Pinterest Research)

Instead of generic AI-generated neon purple-to-cyan gradients, glowing glassmorphism blobs, or cluttered 3-column dashboard templates, **Kanso Ledger** follows a tactile editorial ledger aesthetic:

- **Editorial Paper & Obsidian Ink Palette (`ui/theme/Color.kt`, `Theme.kt`)**:
  - **Editorial Paper (Light Mode)**: Warm Alabaster Paper (`#F6F3EC`), Crisp Ivory Surface (`#FFFDF9`), Sand Stone Elevated Cards (`#EEEAE0`), Hairline Stone Borders (`#E2DDD1`), and Deep Espresso Ink (`#161614`).
  - **Obsidian Ink (Dark Mode)**: Carbon Obsidian (`#111210`), Basalt Surface (`#191B18`), Graphite Hairlines (`#2D302A`), and Warm Chalk (`#F2EFE9`).
  - **Mineral & Botanical Category Tokens**: Terracotta Vermilion (`#C84B31`), Botanical Pine (`#2D6A4F`), Fjord Slate Blue (`#2B5876`), Raw Umber Clay (`#6B5344`), Warm Ochre (`#D9822B`), Muted Iris (`#5A4E7C`), Eucalyptus Sage (`#3D7A68`), and Oxblood Wine (`#8C3B4B`).
- **Tabular Numeral Discipline (`ui/theme/Type.kt`)**:
  - Every currency figure, percentage, and keypad readout enforces `fontFeatureSettings = "tnum"` so digits align vertically like a mechanical ledger.
- **Tactile Custom Calculator Entry Sheet (`ui/components/TactileExpenseSheet.kt`)**:
  - Replaces the clunky system keyboard for entering amounts with an oversized 4×4 tactile numeric keypad supporting **live inline math (`+`, `-`)** (e.g., typing `14.50 + 3.20` computes `$17.70` in real time), haptic feedback, smart merchant auto-completion from your local history, category pills, payment method selector (`Card`, `Cash`, `Instant/UPI`, `Wallet`), and recurring bill toggle.
- **Multi-Segment Proportional Allocation Bar & Daily Rhythm Canvas (`HeroLedgerCard.kt`, `DailyRhythmChart.kt`)**:
  - Displays Total Outflow, Monthly Cap, **Safe-to-Spend / Day** allowance, Pacing Status (`ON PACE`, `PACING WARM`, `OVER TARGET`), an interactive proportional category allocation bar, and a custom Jetpack Compose `Canvas` daily spending bar chart with a dashed daily target line.

---

## 2. 100% Local On-Device Architecture (Zero Cloud / Zero Network Permissions)

- **Zero Network Permissions**: `AndroidManifest.xml` does **not** request `android.permission.INTERNET`. Your financial records never leave your phone.
- **Room SQLite Database (`data/local/KansoDatabase.kt`)**:
  - `ExpenseEntity` (`expenses` table): Stores `id`, `title`, `amount_cents` (`Long` in minor units to prevent floating-point rounding bugs), `category_id`, `payment_method`, `timestamp_epoch_millis` (indexed), `note`, and `is_recurring`.
  - `MonthlyBudgetEntity` (`monthly_budgets` table): Stores per-month overall spending caps keyed by `YYYY-MM`.
  - `CategoryBudgetEntity` (`category_budgets` table): Stores optional per-category monthly spending caps.
- **Local JSON Backup & Data Sovereignty (`data/local/LocalBackupManager.kt`)**:
  - Export your entire local SQLite ledger to a human-readable JSON snapshot (copy to clipboard or save locally), restore from JSON at any time, seed sample data, or wipe the local vault with one tap.

---

## 3. Project Structure

```text
/home/azureuser/android
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/ (themes, colors, strings, adaptive vector launcher icons, backup rules)
        │   └── java/com/kanso/spending/
        │       ├── KansoApplication.kt
        │       ├── MainActivity.kt
        │       ├── data/
        │       │   ├── model/ (SpendingCategory.kt, PaymentMethod.kt)
        │       │   ├── local/
        │       │   │   ├── entity/ (ExpenseEntity.kt, MonthlyBudgetEntity.kt, CategoryBudgetEntity.kt)
        │       │   │   ├── dao/ (SpendingDao.kt)
        │       │   │   ├── KansoDatabase.kt
        │       │   │   └── LocalBackupManager.kt
        │       │   └── repository/ (SpendingRepository.kt, SampleLedgerSeeder.kt)
        │       ├── domain/
        │       │   ├── CurrencyFormatter.kt
        │       │   ├── KeypadExpressionEvaluator.kt
        │       │   ├── SpendingAnalyticsModels.kt
        │       │   └── SpendingAnalyticsEngine.kt
        │       └── ui/
        │           ├── SpendingViewModel.kt
        │           ├── theme/ (Color.kt, Type.kt, Theme.kt)
        │           ├── components/
        │           │   ├── EditorialHeaderBar.kt
        │           │   ├── HeroLedgerCard.kt
        │           │   ├── DailyRhythmChart.kt
        │           │   ├── TransactionLedgerItem.kt
        │           │   ├── TactileExpenseSheet.kt
        │           │   ├── BudgetPlannerSheet.kt
        │           │   └── LocalVaultSheet.kt
        │           └── screens/
        │               └── SpendingDashboardScreen.kt
        └── test/java/com/kanso/spending/domain/
            ├── KeypadExpressionEvaluatorTest.kt
            └── SpendingAnalyticsEngineTest.kt
```

---

## 4. How to Open & Run in Android Studio

1. Open **Android Studio Ladybug (2024.2.1)** or newer.
2. Select **Open** and choose `/home/azureuser/android`.
3. Let Gradle sync (uses **Kotlin 2.0.21**, **Compose BOM 2024.11.00**, **Room 2.6.1 with KSP**, `compileSdk = 35`, `minSdk = 26`).
4. Run the `app` configuration on any Android device or emulator (API 26+).
