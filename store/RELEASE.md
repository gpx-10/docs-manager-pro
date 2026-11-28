# Building and signing a release

## One-time: the upload key

`keystore/upload-keystore.jks` and `keystore.properties` are created on this Mac and **ignored by git**.
`keystore.properties` holds the password. Keep both in a password manager plus an offline copy.

Upload key certificate SHA-256 (Play Console shows the same fingerprint under App integrity):
`99:F2:47:7D:AE:76:FC:B0:11:C2:CF:1D:EB:3A:13:52:2B:50:3A:3D:1D:3C:F5:5D:74:38:CF:3F:44:84:17:F8`

With Play App Signing, Google holds the key that signs what users install. If this upload key is
ever lost, ask Play support for an upload key reset. Users are not affected.

## Every release

1. In `app/build.gradle.kts`, raise `versionCode` by 1 and set `versionName` (for example 1.0.1).
2. Run the tests: `./gradlew testDebugUnitTest connectedDebugAndroidTest` (emulator or phone attached).
   If the release build is installed on that device, uninstall it first
   (`adb uninstall in.llmlabs.docsmanager`): Android refuses to replace a release-signed app
   with the debug-signed test build, and the device tests then do not run.
3. Build: `./gradlew bundleRelease`
4. Upload `app/build/outputs/bundle/release/app-release.aab` in Play Console.

To try the exact release code on a phone first: `./gradlew assembleRelease`, then
`adb install -r app/build/outputs/apk/release/app-release.apk`.

## Checks done for 1.4.0

- 61 device tests and 6 unit tests pass (`./gradlew testDebugUnitTest connectedDebugAndroidTest`).
- Every tool tried by hand on the emulator (Pixel, Android 16), plus 7- and 10-inch tablet layouts.
- Signed bundle verifies (`jarsigner -verify`); about 22 MB, each phone downloads only its own CPU type.
- All six native libraries (MuPDF, Tesseract OCR, Leptonica, libjpeg, libpng, androidx) are 16 KB aligned.
- Merged manifest: still **no `INTERNET`** permission; Tesseract4Android adds none.
- OCR language data (`assets/tessdata/eng.traineddata`, tessdata_fast, Apache-2.0) ships in the app.

## Checks done for 1.0.0

- Signed bundle verifies (`jarsigner -verify`).
- `lib/arm64-v8a/libmupdf_java.so`: segments aligned to 16 KB and stored uncompressed at 16 KB offsets.
- Merged manifest permissions: `MANAGE_EXTERNAL_STORAGE` (optional, user-initiated) and
  `READ_EXTERNAL_STORAGE` up to Android 10 only. **No `INTERNET`**, no `AD_ID`.
- Bundle 12.6 MB; each phone downloads only its own CPU type.
