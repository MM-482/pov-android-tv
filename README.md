# Cameo for Android and Android TV

**Standalone app made by Mark Mateo. Based on the original POV Kodi add-on by [kodifitzwell](https://github.com/kodifitzwell/repo).**

Cameo is a native Android app for browsing movies, shows, and anime, organizing your own videos and saved titles, connecting your playback services, and playing video inside the app. Kodi is not required. This source is **0.4.2-preview** (`versionCode 9`), for Android 8.0 / API 26 and later.

This version extends the Android TV 0.3.0 base to touch devices and foldable windows, and implements features from the supplied **Cameo Windows 0.2.4** source. The regular application's ID remains `dev.pov.tv` so an appropriately signed update can retain existing Android data. A separate **Cameo Preview** build can be installed alongside it. Published downloads and their matching source assets are listed on [GitHub releases](https://github.com/MM-482/pov-android-tv/releases).

[0.4.2 touch navigation and playback fixes](docs/RELEASE_NOTES_0.4.2.md) · [0.4.1 design corrections](docs/RELEASE_NOTES_0.4.1.md) · [0.4.0 feature additions](docs/RELEASE_NOTES_0.4.0.md) · [Feature mapping and limits](docs/CAMEO_ANDROID_PARITY.md) · [Verified build results](docs/VALIDATION_0.4.2.md) · [LibVLC sources and licensing](docs/LIBVLC.md)

## What this version adds

- **Phone and foldable layouts:** native touch sizing, scrollable content, and the same five bottom navigation destinations at every touch width, including unfolded and tablet windows. Wider content adapts independently of the menu. Resize and rotation retain navigation state and the shared playback session. TV keeps its remote-oriented layout and ordered DPAD source/file navigation.
- **Recoverable playback controls:** tapping the video after the controls auto-hide restores a full interaction interval for Media3 and embedded VLC. Touch observation preserves native video taps, button clicks, and scrubbing; touch actions activate on the first tap.
- **Five reference-based appearances:** Glass, Cinema, Slate, Pearl, and Aurora now follow the supplied November 2 phone and TV concepts, with distinct artwork placement, typography, cards, and navigation. Five independently selectable native launcher icons are available in Blue, Violet, Pearl, Gold, and Mint. Theme changes do not switch the app icon.
- **Local videos, watchlist, and playlists:** import selected videos or a folder through Android's document picker; search the local library; save catalog titles, exact episodes, local videos, and service files; rename and reorder playlists; use previous/next and automatic advancement for playable entries.
- **More saved connections:** independent saved accounts cover all 22 service types, including playback providers, media servers, anime trackers, Trakt, Simkl, MDBList, and subtitle providers. Actions on a saved tracker/subtitle account stay tied to that account; existing automatic-tracking connections remain separate.
- **Installed add-ons:** install, enable, disable, or remove Stremio-compatible manifests; open catalogs with supported search, genre, and paging; retain exact title/episode identities for source and subtitle lookup.
- **Downloads:** explicitly select a source or service file and a destination; monitor progress or cancel. Easynews adds local NZB import, per-file selection, encrypted NNTP transfer, and yEnc integrity checks.
- **Playback options:** speed, sleep timer, subtitle appearance, VLC subtitle timing and chapters, playback details, and playlist navigation. Media3 and embedded VLC retain their existing shared fullscreen/mini-player session.
- **Public metadata cache:** recently read supported public catalog metadata remains available during short outages. This does not make account libraries, source resolution, or remote video available offline.
- **Direct streams and troubleshooting:** open an HTTPS or validated local-server media URL, review offline app/player diagnostics, optionally check fixed public endpoints, and save a sanitized report to a chosen document.

Implementation details and service-specific boundaries are in the [parity record](docs/CAMEO_ANDROID_PARITY.md).

## Getting started

1. On a phone, use **Browse**, the **Movies / Shows** category tabs, or **Anime** to browse public metadata. TV has separate **Movies** and **Shows** destinations. **Explore** opens a featured title's details; choose a source or your own file to play it.
2. Open **Library → Import videos** or **Import a folder** for local content. Files remain in their original location. Removing a library entry does not delete the video.
3. Open **Settings → Connections** to add a saved account and explicitly choose it for supported operations. Use **Existing accounts, tracking, and subtitles** for the retained Android connections, including the original automatic-tracking configuration. Service-specific plans, keys, access tokens, or registered application identifiers may be required.
4. Use **+ Library** on the featured title to save it to the device watchlist. Repeating the action keeps one saved entry. A title or file's options also provide watchlist and playlist actions. A catalog playlist entry still opens source selection; it does not automatically create a cloud transfer.
5. Open **Settings → Add-ons** to install a compatible HTTPS manifest or a validated private-LAN HTTP manifest. An enabled add-on can contribute its declared catalogs, metadata, sources, or subtitles. A `stremio://` address is normalized to HTTPS.
6. Use **Download** on a source or service file, then choose a new destination in Android's save dialog. For Easynews, open its saved connection, choose **Import NZB**, select the NZB document, choose one contained file, and choose its destination.
7. **Settings → Open a stream URL** accepts a media address for explicit playback. **Settings → Troubleshooting and diagnostic report** offers a local report and a separate public-connectivity check; reports are never uploaded automatically.

Cameo supplies no subscriptions, media-service credentials, API keys, Trakt Client ID, or Simkl Client ID. Account actions use the account you connect. Saving a replacement account creates a new connection identity so an old saved file is not silently retargeted to another account.

## Appearance and window behavior

Open **Settings → Appearance**. Glass is the fresh-install default.

| Appearance | Visual layout |
| --- | --- |
| Glass | Cool blue, immersive artwork, translucent surfaces, and a rounded TV rail. |
| Cinema | Near-black and champagne, condensed film titles, fine rules, and horizontal TV navigation. |
| Slate | Graphite and emerald, a framed hero, clear touch pills, and a permanent TV rail. |
| Pearl | Ivory and slate blue, serif display headings, framed artwork, and a split TV hero. |
| Aurora | Midnight violet and cyan, restrained atmosphere, capsule navigation, and a panoramic TV hero. |

Existing Essential, Premiere, Atelier, and Horizon preferences retain their stored identifiers and display as Slate, Cinema, Pearl, and Aurora. Existing Studio preferences remain readable; Studio is not in the new five-theme picker. Earlier Android appearance preferences still migrate without clearing accounts or playback history.

The touch layout uses the **current window size in dp**, including split-screen and fold/unfold changes. **Home / Browse / Anime / Library / Search** remain at the bottom at every touch width. Content at least 560dp wide can use a split feature composition, including the tested 720dp unfolded window. The mini-player remains above the bottom menu. Both browsing and fullscreen playback follow the device's orientation preference on mobile; TV retains landscape presentation. The renderer has not been verified on a physical Galaxy Z Fold 8.

## Playback and controls

| Action | Behavior |
| --- | --- |
| Tap the video after controls hide | Restore the controls without pausing, replacing, or restarting the playback session. |
| Drag the timeline | Keep controls available throughout the scrub and start a fresh hide interval when the gesture finishes. |
| Back from fullscreen | Browse with the same session in the in-app mini-player. |
| Tap/select the mini-player | Return to fullscreen playback. |
| Hold the mini-player, or use the TV Menu action | Open Return to video / Stop playback. |
| Hold a Continue Watching title | Open Resume / Remove from Continue Watching. |
| Playback options | Audio, subtitles, speed, sleep, chapters where available, volume boost, and playback details. |
| Previous/next playlist item | Follow the saved playlist order; catalog entries require explicit source selection. |
| Stop | Save local progress and release the playback session. |
| All Cameo screens leave the foreground | Pause; resume on return only if playback was previously active. |

Media3 is the default engine; Settings selects the engine for the next video. Recognized HLS/DASH URLs and protected requests can route to Media3 while preserving the saved VLC preference. Chapter selection and subtitle timing are available through VLC. Caption styling depends on subtitle format; applying VLC caption defaults reloads the current video at its position. Media3 decoded-PCM boost remains separate from system volume and is unavailable on unsupported routes.

The mini-player is inside Cameo. This version does not add a system picture-in-picture window, a desktop-style pop-out, or a permanent background playback/download service.

## Downloads, data, and privacy

Downloads run while the app process remains alive. Interrupted jobs are marked for a new start after process restart; partial transfers are not resumed. Complete progressive files and verified NZB files are the supported download paths; there is no HLS/DASH offline-packaging engine or RAR/PAR2 repair/extraction. The selected document provider must support the required file operations. Temporary downloads also require free app-cache space before the completed file is copied to your chosen destination.

Account credentials and configured add-on addresses use the existing encrypted private store and Android Keystore. Library references, playlists, watchlist, download history, and local playback history use private app storage; they are not all additionally encrypted. Saved service descriptors retain account/file identities rather than resolved stream URLs or authorization headers. Android backup exclusions remain enabled.

Public catalog requests use HTTPS. User-configured media servers and add-ons may use HTTP only for validated private, loopback, or local-link destinations; request authentication is scoped to the intended origin. The app has no developer-operated account, advertising, or telemetry service. See [current data/network behavior](docs/CAMEO_ANDROID_PARITY.md#data-and-network-behavior) and the retained [privacy baseline](docs/PRIVACY.md).

## Building and installing an update

**Use the side-by-side Cameo Preview candidate for initial testing when the original installation's signing key is unavailable.** Its application ID is `dev.pov.tv.cameopreview`, its launcher label is **Cameo Preview**, and its version is `0.4.2-preview-side-by-side`. Its first installation starts with separate, fresh app data and leaves the installed `dev.pov.tv` app and its data intact. Existing regular-app accounts, library entries, and history are not copied automatically. A Preview update signed with the retained Preview key can update the earlier Cameo Preview installation in place.

The source uses Java 17, Gradle 8.14.3, compile API 36, target API 35, and minimum API 26. On Windows, run from the project directory with PowerShell 7:

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1
```

Bootstrap downloads checksum-pinned tooling into the project tooling directory. The default build runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug`. The existing `POV_BUILD_OUTPUT` environment variable remains the build-output contract; the debug APK is under `$env:POV_BUILD_OUTPUT/outputs/apk/debug/app-debug.apk`. [Packaging](scripts/package.ps1) creates the APK/source pair and an external `SHA256SUMS.txt`; packaging alone does not publish a release.

Build the separate candidate with the script's task parameter:

```powershell
.\scripts\build.ps1 -Tasks @(':app:assemblePreview')
```

Its APK is under `$env:POV_BUILD_OUTPUT/outputs/apk/preview/app-preview.apk`. With an already configured Gradle/Android toolchain, the equivalent task is `gradle :app:assemblePreview`. The preview build inherits debug signing and uses its separate application ID; the default debug build remains `dev.pov.tv` / **Cameo**.

**Development APKs are debug-signed. An in-place update requires the same signing certificate/private key as the installed APK.** Keeping the application ID preserves compatibility only when the signing identity also matches. Retain and use the original signing key for updates. If that key is unavailable, keep the existing installation and its data intact while arranging a correctly signed build. Signing keys and passwords must stay outside source control.

## Verification scope

The final local run passed **570 tests in 77 suites**, with no failures, errors or skipped tests. Both APK builds succeeded; Android lint reported **0 errors and 64 warnings**. All **15 native preview renders** passed their geometry checks. The [0.4.2 validation record](docs/VALIDATION_0.4.2.md) records the exact artifact identities and scope. Earlier [0.4.1](docs/VALIDATION_0.4.1.md) and [0.4.0](docs/VALIDATION_0.4.0.md) validation records remain historical.

Automated checks cover the adaptive layout, theme/icon independence, configuration-change navigation/session retention, TV navigation regressions, saved libraries/playlists, service contracts, download handling, NZB/yEnc integrity, and player options. The opt-in preview fixture renders real native controls at the original phone canvas sizes, a 720dp unfolded window, and the TV canvas. Its fictional catalog and optional artwork sampled from text-free reference regions are test fixtures only. Preview metadata identifies these as **simulated native layouts**, not physical-device screenshots.

The current Android work has **not** been exercised with live paid accounts, a physical Galaxy Z Fold 8, or a physical TV. Historical Sony captures and audits under `docs/previews/0.3.0/` and `docs/AUDIT_0.3.0.md` apply to their recorded 0.3.0 candidates.

The Windows source was available for feature comparison. All ten original November 2 phone and TV concepts were supplied for the 0.4.1 visual correction. The production app renders live catalog artwork and native controls; it does not display flattened concept screenshots. Exact artwork and system bars depend on the content and device. The actual Mac source remains unavailable, so complete Mac parity is unverified.

## License and history

Application source is provided under [GNU GPL version 3](LICENSE). Original POV 6.10.06 is credited to [kodifitzwell/repo](https://github.com/kodifitzwell/repo), revision `2ea2eb7f330988ade16d388d3832b8e4b60c6b24`. The original developer is not affiliated with this port. Cameo's new Android launcher marks are native vector assets; existing upstream asset and dependency notices remain in place.

AndroidX Media3, Kotlin, OkHttp, and Okio retain their Apache License 2.0 notices; the [complete license](app/src/main/assets/Apache-2.0.txt) is included. Embedded LibVLC/native components and bundled fonts retain their upstream licenses and [source obligations](docs/LIBVLC.md). Runtime artwork and service names belong to their respective owners. The [runtime notices](app/src/main/assets/NOTICE.txt), including the Public Suffix List notice, and [font notices](app/src/main/assets/FONT_NOTICES.md) remain included.

Historical [0.3.0 setup](docs/INSTALL_0.3.0.md), [change notes](docs/RELEASE_NOTES_0.3.0.md), [audit](docs/AUDIT_0.3.0.md), design galleries, and earlier release records are preserved. They describe their own versions and do not certify this 0.4.2 candidate.
