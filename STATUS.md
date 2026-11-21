# Implementation status

Tracks what's built against [`docs/SPEC.md`](docs/SPEC.md), what's been verified against the
live APIs, and what still needs testing. Update it with every change that moves a build-order
step forward.

## Build order

| Step | State |
| --- | --- |
| 1. Setup (developer apps) | Done by Roger, November 21, 2026 (see the spec's Setup status) |
| 2. Core | Not started (skeleton only) |
| 3. Collectors | Not started |
| 4. CLI and MCP | Not started |
| 5. Obsidian writer | Not started |
| 6. Windows UI | Not started (placeholder shell with the six sections) |
| 7. Android | Not started (placeholder app showing the shared shell) |
| 8. Posting | Not started |
| 9. Hardening | Not started |

## Skeleton (November 21, 2026)

- Modules `core`, `ui`, `desktopApp`, `androidApp`, per the spec's Architecture section.
- Toolchain: Kotlin 2.4.21, Compose Multiplatform 1.12.2, Android Gradle Plugin 9.5.2,
  Gradle 9.9.2, JDK 21. Android compileSdk/targetSdk 37, minSdk 29.
- Android application ID: `io.github.rmac0001.foursight` (approved by Roger).
- CI (GitHub Actions): format check, tests and the Android build on Linux; the Windows MSI
  installer on a Windows runner.

## To verify live

From the spec's "Still open" list; neither can be known until it's tested.

- [ ] **TikTok first sign-in:** the Display API returns the account's stats in Sandbox.
- [ ] **Drive across devices:** files the Windows client creates under `drive.file` are visible
  to the Android client. Test before building the merge logic on it.

## API details checked

None yet. Record each endpoint and metric name here with the date and the reference page it was
checked against.
