# Google Play submission: Docs Manager Pro 1.1.0

Every answer below matches what the app does in version 1.0.0 (package `in.llmlabs.docsmanager`).
Copy them into Play Console as written. If the app changes, update this file first.

## Before you open Play Console

| # | Step | Why |
|---|---|---|
| 1 | ✅ Done 24 Nov 2026: the privacy policy is live at https://llmlabs.in/apps/docs-manager-pro/privacy | Play requires a working privacy policy URL, and the app links to it |
| 2 | ✅ Done 24 Nov 2026: source is public at https://github.com/gpx-10/docs-manager-pro | MuPDF is AGPL: anyone who gets the app must be able to get its source. The app's About screen links there |
| 3 | ✅ Done 24 Nov 2026: video at https://www.youtube.com/watch?v=TrmWxzDRxI4 | The All files access declaration needs a video link |
| 4 | Back up `keystore/upload-keystore.jks` and `keystore.properties` (password manager plus an offline copy) | They sign every update. If lost, Play support can reset the upload key, but it takes days |
| 5 | Build: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` | The file you upload |

## 1. Create app

- **App name:** Docs Manager Pro
- **Default language:** English (United States), en-US
- **App or game:** App
- **Free or paid:** Free
- Tick both declarations (Developer Program Policies, US export laws).

## 2. App content (Policy → App content)

### Privacy policy
`https://llmlabs.in/apps/docs-manager-pro/privacy`

### App access
**All functionality is available without special access.** No login, no account.

### Ads
**No, my app does not contain ads.**

### Content rating
Start the questionnaire.
- **Email:** trestle1011@gmail.com
- **Category:** All other app types (Utility, Productivity, Communication or Other)
- Violence: No · Sexuality: No · Language: No · Controlled substances: No · Gambling: No
- Does the app allow users to interact or exchange content with other users? **No**
- Does the app share the user's current physical location with other users? **No**
- Does the app allow users to purchase digital goods? **No**
- Is the app a web browser or search engine? **No**
- Expected result: **Everyone / PEGI 3 / IARC 3+**

### Target audience and content
- **Age groups:** 13–15, 16–17, 18 and over. **Do not tick any age under 13**: that brings in the Families policy, which this app is not built for.
- Could the app unintentionally appeal to children? **No**

### News apps
**No**

### Data safety
- Does your app collect or share any of the required user data types? **No**
- Why this is true: the app has **no INTERNET permission**, so nothing can leave the phone. Recent files, folders and settings stay in the app's private storage. Google's definition of "collect" means sending data off the device, which this app cannot do.
- When asked about data deletion, the answer is that no data is collected.

### Advertising ID
Does your app use advertising ID? **No** (the app does not declare `AD_ID`).

### Government apps · Financial features · Health
Not a government app · No financial features · No health features.

## 3. Sensitive permission: All files access (MANAGE_EXTERNAL_STORAGE)

Play Console asks for this after you upload the bundle (Policy → App content → **Permissions declaration form**, or a prompt on the release page).

- **Core functionality:** Document management
- **Video:** https://www.youtube.com/watch?v=TrmWxzDRxI4 The video shows: the in-app card explaining the option → Allow full access → Android's All files access switch → the device-wide document list (including Download) → opening a PDF.

**Describe how your app uses the permission:**
> Docs Manager Pro is a document reader and manager. Its Files tab can list every PDF, EPUB, XPS, CBZ and image document on the device in one place, with type filters, so users can find and open their documents wherever they are stored, including the Download folder. The permission is optional. It is requested only when the user taps "Allow full access" on an in-app card that explains what it does, and the app keeps working without it (folder-by-folder access through the system picker). The app only reads files to list and display them. It never modifies, deletes or uploads files, and it has no internet permission.

**Why alternatives such as the Storage Access Framework or MediaStore are not enough:**
> The Storage Access Framework cannot grant access to the Download folder or the root of shared storage: since Android 11, ACTION_OPEN_DOCUMENT_TREE refuses them, and that is where most users keep their PDFs. MediaStore lets an app read only other apps' images, video and audio, so it cannot list PDFs, e-books and other documents saved by browsers, email or messaging apps. Picking files one at a time with the system picker (which the app also offers) cannot provide the core feature: an up-to-date, device-wide list of the user's documents.

## 4. Store listing (Grow → Store presence → Main store listing)

- **App name (30 max):** Docs Manager Pro
- **Short description (80 max, this is exactly 80):**
  `Read PDFs and e-books and find every document on your phone. No ads, no account.`
- **Full description:**

```
Docs Manager Pro is a fast, private reader and manager for the documents on your phone.

READ
• PDFs with smooth scrolling, pinch and double-tap zoom, and go to page
• E-books (EPUB), XPS, comic books (CBZ) and images
• Bigger or smaller e-book text: the book re-flows to fit your screen
• Night mode that swaps light and dark but keeps charts and colours readable
• Tap a page to hide the controls and read full screen
• Opens password-protected PDFs
• Picks up on the page where you left off

TOOLS
• Merge PDF: join several PDFs into one, in the order you choose
• Split PDF: break a PDF into parts by page ranges, or one file per page
• Results are saved as new files in Download/Docs Manager Pro; your originals are never changed

FIND
• Recent files, with the page you were on
• Add a folder (Documents, SD card, Drive and more) to see all its documents
• Optional: see every document on your phone in one list, including Download, with PDF, e-book and image filters. This uses Android's All files access only if you turn it on, and you can turn it off at any time
• Open documents from other apps with "Open with" or "Share"

PRIVATE BY DESIGN
• No ads, no account, no tracking
• No internet access: your documents never leave your phone
• Free and open source under the GNU AGPL v3

More document tools from LLM Labs are on the way in free updates.
```

- **App icon:** `store/listing/icon-512.png` (512 × 512, 32-bit PNG)
- **Feature graphic:** `store/listing/feature-graphic.png` (1024 × 500)
- **Video (optional):** upload `store/promo-video.mp4` (1920 × 1080, 1 min 19 s, no sound) to YouTube as **Public** or **Unlisted** with ads turned off, then paste its link. Play needs a YouTube link, not a file. This is separate from the All files access video.
- **Phone screenshots (8):** `store/listing/phone-1-home.png` … `phone-8-files.png` (1080 × 1920), in this order: Home, PDF viewer, Merge, Split, Saved results, Night mode, E-book, Files
- **7-inch tablet screenshots:** `store/listing/tablet-7/` (1200 × 1920): home, files, viewer, night
- **10-inch tablet screenshots:** `store/listing/tablet-10/` (1600 × 2560): home, files, viewer, night
- Tablet screenshots are optional, but without them Play doesn't feature the app on tablets.
- The HTML used to lay out the screenshots, feature graphic and video cards is in `store/art/`.
- **App category:** Productivity
- **Tags:** pick from Play's list. Closest: PDF reader, Document management, File management, E-book reader
- **Contact email:** trestle1011@gmail.com
- **Website:** https://llmlabs.in

## 5. Release

New personal developer accounts must run a **closed test with at least 12 testers for 14 days** before Production unlocks. If this account already published BCC's Reader to Production, that step is done.

1. **Release → Production** (or **Closed testing** if Production is locked) → **Create new release**.
2. **App signing:** accept **Play App Signing** (Google keeps the app signing key; your file is the upload key).
3. Upload `app-release.aab`.
4. **Release name:** `1.0.0 (1)`
5. **Release notes** (for 1.1.0, versionCode 2, if you upload that instead of 1.0.0, use the 1.1.0 notes below):
```
<en-US>
First release: read PDFs, e-books, XPS, comics and images. Night mode, e-book text size, recent files that reopen where you left off, and folders, or optionally every document on your phone in one list. No ads, no account, no internet.
</en-US>
```
   1.1.0 notes:
```
<en-US>
New: Merge PDF and Split PDF. Join several PDFs into one, or break a PDF into parts by page ranges or page by page. Results are saved to Download/Docs Manager Pro.
</en-US>
```
6. **Countries/regions:** all, or start with India.
7. **Send for review.** New apps with All files access usually take several days, sometimes more than a week.

## Why each common rejection reason is covered

| Rejection reason | How 1.0.0 handles it |
|---|---|
| All files access not justified | Document management is a permitted use. The declaration explains why SAF and MediaStore can't do it, the video shows the flow, and the permission is optional and explained in the app before it is requested |
| Permission asked without explanation | The in-app card explains it and offers folder-by-folder access instead; Manage turns it off |
| Misleading listing | The listing and screenshots show only features in 1.0.0. Unbuilt tools are a plain "On the way" list, not buttons |
| Broken or placeholder features | No tile does nothing. Every quick action and tool tile works |
| Privacy policy missing or doesn't match | The policy describes every piece of data, the optional permission, and that there is no internet access |
| 16 KB page size (Android 15+) | MuPDF's 64-bit library is 16 KB aligned and stored aligned in the bundle (checked) |
| Target API level | targetSdk 36 |
| 64-bit | arm64-v8a included |
| Open-source licence | The About screen gives the AGPL notice, credits MuPDF and links to the source |
