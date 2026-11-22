# FourSight

Roger's personal creator-analytics and posting app for the One Fat Man's Journey channel.
Kotlin Multiplatform + Compose Multiplatform; one codebase for Windows and Android.

**The spec is the source of truth: [`docs/SPEC.md`](docs/SPEC.md).** Read it before changing
anything. It's a build-only copy of Roger's vault spec note, which stays the master; don't edit
`docs/SPEC.md` on its own. `STATUS.md` tracks what's built, what's verified live and what still
needs testing.

**The repo is public.** No secrets, account or app IDs, vault paths or personal data in code,
commits, test fixtures or logs.

## Rules for Claude Code (from the spec)

- Build from this spec. If something is ambiguous, list the options and tradeoffs and ask Roger. Don't make architectural calls alone.
- Check the current platform API reference before using any endpoint or metric name. Don't invent API capabilities.
- Don't fabricate, estimate without a label, or silently substitute analytics values.
- No scraping or unofficial sign-in workarounds without Roger's explicit approval.
- No embedded AI, no server, no extra infrastructure, no commercial features.
- Ask Roger before creating, changing or deleting files outside the repo (including the vault).
- American English in all code comments, UI text and docs.

## Also

- **No secrets anywhere in the repo, code, logs, exports or MCP output.** Client IDs, secrets
  and tokens live in Windows Credential Manager or the Android Keystore. When a key is needed,
  tell Roger exactly what to enter and where; he enters it himself.
- **Mock data** lives only in tests and developer builds, is always labeled, and never appears
  as live data.
- **Scope:** the channel comes first. Push back on anything outside the spec; tabled ideas stay
  tabled until Roger moves them into the spec.
- Work on a branch, never directly on `main`.

## Commands

- `./gradlew check`: format check (Spotless + ktlint), all tests, Android lint. Run before committing.
- `./gradlew spotlessApply`: fix formatting.
- `./gradlew :core:desktopTest`: core unit tests on the JVM.
- `./gradlew :desktopApp:run`: run the desktop app.
- `./gradlew :desktopApp:packageMsi`: Windows installer (Windows only; CI builds it).
- `./gradlew :androidApp:assembleDebug`: Android debug APK.

## Layout

- `core/`: shared logic (`commonMain`), no UI. Platform clients, models, database, metrics,
  collector, sync, and the query services used by the UI, CLI and MCP. No calculation exists
  twice: put it here.
- `ui/`: Compose Multiplatform screens shared by both apps.
- `desktopApp/`: Windows entry point (window, tray, CLI, MCP server, Obsidian writer).
- `androidApp/`: Android entry point (WorkManager collector, Glance widget).
- Package root `io.github.rmac0001.foursight`. The Android application ID
  `io.github.rmac0001.foursight` is permanent: the OAuth clients are registered against it.

## Conventions

- Versions live in `gradle/libs.versions.toml`. Use stable releases and check the current one
  before adding or bumping a dependency.
- Formatting: ktlint official style, 4 spaces, 120 columns. Don't disable rules without a
  written reason.
- Keep platform-specific code behind `expect`/`actual` or interfaces in `core`, so logic stays
  testable on the JVM without a device or the network.
