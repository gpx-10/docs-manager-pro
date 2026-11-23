# Docs Manager Pro

A fast, private document reader and manager for Android, from [LLM Labs](https://llmlabs.in).

- Read PDFs, e-books (EPUB), XPS, comic books (CBZ) and images
- Smooth scrolling, pinch and double-tap zoom, go to page, night mode, adjustable e-book text
- Opens password-protected PDFs and reopens files on the page you left
- Recent files, folders you choose, or (optionally) every document on the phone in one list
- No ads, no account, no internet access: documents never leave the phone

Privacy policy: https://llmlabs.in/apps/docs-manager-pro/privacy

## Build

Requires the Android SDK (compile SDK 36) and JDK 17+.

```
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew connectedDebugAndroidTest   # device tests (emulator or phone attached)
```

Release builds are signed with a key that is not in this repository; see `store/RELEASE.md`.

## Licence

GNU Affero General Public License v3.0 or later (see `LICENSE`).
Built with [MuPDF](https://mupdf.com) © Artifex Software, Inc. (AGPL v3) and Android Jetpack libraries (Apache 2.0).
