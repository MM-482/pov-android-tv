# Cameo Android 0.4.0-preview

Version name: **0.4.0-preview**  
Version code: **7**  
Regular application ID: **dev.pov.tv**  
Side-by-side candidate ID: **dev.pov.tv.cameopreview**  
Minimum Android version: **8.0 / API 26**

This preview continues the Android TV 0.3.0 source as **Cameo**, adds native phone/foldable layouts, and brings across feature families from the supplied Cameo Windows 0.2.4 source. It keeps the existing Android account stores, playback history, TV navigation, and Media3/LibVLC player.

## Main changes

### Mobile, foldable, and TV presentation

- Native dp/sp touch layouts replace the TV canvas scaling on phones and tablets.
- Five bottom navigation destinations appear below 600dp; wider touch windows use a navigation rail.
- Home, title cards, source/file lists, anime pages, and the mini-player adapt to the available window.
- Main and fullscreen player activities accept mobile orientation and fold/resize changes. TV remains landscape.
- The handled configuration-change path keeps the current route, Back stack, selected season, scroll, and shared playback session. Main also handles display-density/font-scale changes in place.
- **Glass, Cinema, Slate, Pearl, and Aurora** are the selectable appearances. Previous theme IDs remain compatible; existing Studio remains readable.
- Five native Cameo launcher icons and corresponding TV banners are selected independently from appearance.

### Library and downloads

- Import selected videos or a directory using Android's document picker, with local search and paging.
- Keep a device watchlist and saved playlists of catalog titles, exact episodes, local videos, or service files.
- Create, rename, delete, reorder, remove, and play playlist entries. Previous/next and file auto-advance follow the saved order; catalog entries still ask for a source.
- Download a selected service/source file to a user-chosen new destination; show progress, cancel, and open completed downloads.
- Import a local NZB through Easynews, select a contained file, and download through NNTP TLS with yEnc size, CRC, and segment-order checks.
- Open an HTTPS or validated local media-server stream URL from Settings. Direct stream URLs remain session requests rather than saved account credentials.
- Reject recognized HLS/DASH manifest downloads; this preview does not package segmented streams as offline movies.

### Connections and catalogs

- Independent saved connections add Debrid-Link, put.io, Seedr WebDAV, Jellyfin, Emby, Plex, WebDAV, AniList, MyAnimeList, and Kitsu alongside the existing provider set.
- All 22 Windows connection types support independent saved profiles, including Trakt, Simkl, MDBList, OpenSubtitles, SubDL, and SubSource. Explicit profile actions remain account-bound and do not switch the original automatic-tracking credentials.
- Service browsing and saved entries retain exact account/file/edition identities. Account replacement does not silently redirect old entries.
- Real-Debrid includes a generated-download-history category with fresh resolution of the selected file.
- Source preparation uses explicit choices, exact file selection, and bounded readiness checks for supported providers.
- Stremio-compatible add-ons can be installed, enabled/disabled, and removed over HTTPS or validated private-LAN HTTP. `stremio://` addresses normalize to HTTPS. Declared catalogs support their available search, genre, and paging; source and subtitle lookup retains exact episode IDs.
- A bounded public Cinemeta/Kitsu metadata cache provides a short-term fallback during outages. It does not cache account libraries or media streams.

### Player and troubleshooting

- Playback speed, sleep timer, subtitle size/color/background/position defaults, playback details, and playlist navigation.
- VLC chapter navigation and subtitle timing; unavailable capabilities are explained in the player UI.
- Existing Media3 decoded-PCM boost, selected audio/subtitle tracks, foreground pause/resume behavior, and shared fullscreen/mini session remain.
- Review an offline diagnostic report or explicitly run fixed public connectivity checks, then save the report through Android's document picker.
- Diagnostic reports exclude account names, credentials, private/media URLs, file paths, viewing titles, response bodies, and raw exception messages. Reports are not automatically uploaded.

## Existing Android features preserved

The preview retains ordered DPAD navigation and exact source/file Back restoration; identified Trakt tracking and account-bound Up Next; separately consented MDBList/Simkl reporting; Continue Watching removal without clearing resume/external history; local subtitle import and the three online subtitle providers; manual anime matching; and the established Media3/VLC compatibility policies.

The package/application identity remains `dev.pov.tv`. Appearance/icon changes do not reset service accounts or history. Original license, dependency/font notices, earlier audit records, and historical galleries remain in the source.

## Installation and signing

For initial testing without the original signing key, use **Cameo Preview**, the separate side-by-side build. Its launcher label is **Cameo Preview**, application ID is `dev.pov.tv.cameopreview`, and version name is `0.4.0-preview-side-by-side`. It has fresh app data and leaves the existing regular installation and its accounts/history intact. It does not automatically copy data from the regular app.

Build this candidate with `gradle :app:assemblePreview`, or on the supplied PowerShell workflow:

```powershell
.\scripts\build.ps1 -Tasks @(':app:assemblePreview')
```

The output is `$env:POV_BUILD_OUTPUT/outputs/apk/preview/app-preview.apk`. This build inherits debug signing. The default debug build continues to use `dev.pov.tv` and the regular **Cameo** label.

The regular packaging script names its outputs:

- `Cameo-Android-0.4.0-preview.apk`
- `Cameo-Android-0.4.0-source.zip`
- `SHA256SUMS.txt`

These names describe the packaging outputs, not a claim that a GitHub release has already been published. The separately delivered test candidate is named `Cameo-Android-0.4.0-preview-side-by-side.apk`; its exact identity, size, signature, and digest are recorded in [validation](VALIDATION_0.4.0.md).

Development builds are debug-signed. **An update over an existing installation requires the original signing certificate/private key as well as the unchanged application ID.** A newly generated debug key does not satisfy that requirement. Keep the installed app and its data intact if the original key is unavailable, and arrange a build signed with the original key. Signing material must not enter the source archive.

## Verification scope

The final local run passed **541 tests across 76 suites**, with **0 failures, 0 errors, and 0 skipped tests**. Fresh debug and preview builds both succeeded. Android lint reported **0 errors and 64 warnings**. Both APKs passed signature and ZIP-integrity checks; their compiled launcher/resize settings and bundled notices were inspected. Exact hashes and the verification boundaries are in [VALIDATION_0.4.0.md](VALIDATION_0.4.0.md).

Automated coverage includes mobile and TV view behavior, theme/icon independence, resize/session retention, local stores/playlists, provider/account contracts, download failures and cancellation, NZB/yEnc integrity, advanced player options, and network/credential boundaries. These are local JVM/Robolectric and build checks, not physical-device or live-service acceptance results.

The opt-in native preview fixture produces five appearances at 360dp and 720dp with synthetic catalog/artwork. These are labelled **SIMULATED NATIVE LAYOUT** and are not physical-device captures.

No live paid-account, server, tracker-write, or online subtitle validation has been performed for this candidate. No physical Galaxy Z Fold 8 or physical TV has been tested with this build. Earlier Sony 0.3.0 results apply only to their recorded APKs.

## Known limits

- The actual Mac source and original November 2 concept images were unavailable. This release makes no complete Mac-parity or pixel-match claim.
- Downloads require the app process to stay alive, a compatible document provider, and temporary cache space. Interrupted downloads start again; there is no permanent background transfer service or reboot resume.
- No HLS/DASH offline packaging, RAR extraction, or PAR2 repair.
- Media-server playback covers stored files. Negotiated transcoding, live opening sessions, casting, and server playback-session reporting are outside scope.
- AniList/MyAnimeList/Kitsu updates are explicit; automatic anime-tracker reporting and OAuth refresh are not added.
- Source/provider metadata and subscriptions determine available files and actions. Cached metadata cannot replace online source resolution or certify playback support.
- The mini-player is inside the app. Windows desktop pop-out/window controls, system picture-in-picture, and Apple AVKit/AirPlay are not reproduced.
- Local library/history/preferences are app-private but are not all additionally encrypted. The encrypted account store and backup exclusions remain.

See [CAMEO_ANDROID_PARITY.md](CAMEO_ANDROID_PARITY.md) for the source-to-source mapping, exact limits, and current data/network behavior. Build and usage instructions are in the [README](../README.md).

