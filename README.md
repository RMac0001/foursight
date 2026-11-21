# FourSight

A personal creator-analytics and posting app for the One Fat Man's Journey channel. It reads
TikTok, YouTube, Facebook Page and Instagram analytics through each platform's official API,
keeps a daily history, and runs on Windows and Android from one Kotlin Multiplatform codebase.
Personal use only.

- Spec: [`docs/SPEC.md`](docs/SPEC.md)
- Status: [`STATUS.md`](STATUS.md)
- Public home, privacy policy and terms: <https://rmac0001.github.io/foursight-site/>

## Building

Requires JDK 21. Android builds need the Android SDK (Android Studio installs it; or set
`ANDROID_HOME`).

```bash
./gradlew check                      # format check, tests, lint
./gradlew :desktopApp:run            # run the desktop app
./gradlew :desktopApp:packageMsi     # Windows installer (on Windows)
./gradlew :androidApp:assembleDebug  # Android debug APK
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

## Secrets

No client keys, secrets or tokens are stored in this repo. They live in Windows Credential
Manager and the Android Keystore on each device.
