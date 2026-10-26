# POV Standalone 0.3.0 preview

Standalone app made by Mark Mateo. Based on the original POV Kodi add-on by kodifitzwell.

These notes describe the 0.3.0-preview source and reviewed local artifacts. Consult [public releases](https://github.com/MM-482/pov-android-tv/releases) for published downloads; the version-specific destination is [v0.3.0-preview](https://github.com/MM-482/pov-android-tv/releases/tag/v0.3.0-preview).

## Six native themes

- **Glass** introduces a floating glass-style rail, expansive artwork, white actions, and soft white focus. It is the fresh-install default.
- **Essential** uses the familiar dark rail with cyan actions and compact watching cards.
- **Premiere** combines immersive artwork, serif feature titles, and warm gold focus.
- **Atelier** uses an ivory canvas, top navigation, and a split text/artwork feature.
- **Horizon** pairs top navigation and wide artwork with cyan/violet actions and luminous focus.
- **Studio** uses a large watching feature, an episode column, and browse shortcuts in a dashboard composition.

Choose a theme under **Settings → Appearance**. Each provides native navigation, title actions, focusable cards, and live content. Generated images appear in the picker as design references with sample titles. Home displays real catalog artwork and saved viewing progress. Android 8 glass effects use tinted gradients and borders. The interface bundles openly licensed Inter and Cormorant Garamond fonts, their original SIL Open Font Licenses, and source attribution; it makes no runtime font-service request.

Existing appearance choices migrate to their closest new designs: Cinematic → Essential, Editorial → Atelier, Neon → Horizon, Cinema → Premiere, and Dashboard → Studio. Appearance changes retain playback preferences, connected accounts, and viewing progress. The POV wordmark retains the original project's visual identity and attribution.

Studio's Up Next column can use the connected Trakt account's watched-show and episode-progress reads. It falls back to discovery or local watching entries when that optional data is unavailable. Fetching Up Next does not mark episodes watched.

## Playback and subtitles

- Settings offers Media3 or embedded VLC for the next video. The TV interface, themes, and shared fullscreen/mini-player remain part of POV. A separate VLC installation is unnecessary.
- A compact square Stop button sits between Play/Pause and Forward in the bottom controller. One Right press from Play/Pause reaches Stop.
- Continue Watching titles have Resume and Remove actions through hold-Select or Menu. Removal is local to POV and preserves watched history on external services. Periodic progress saves do not restore a dismissed title; intentionally playing it again does.
- The Subtitles menu can import a local SRT document through Android's document picker. Available storage, USB, and network locations depend on the document providers installed on the TV. POV does not claim to provide its own SMB file browser.
- Settings stores a preferred subtitle language. OpenSubtitles, SubDL, and SubSource use the owner's service credentials, with explicit search/result selection/download in the player. Provider quotas and service terms apply.
- VLC uses its own decoder and track APIs. Existing Media3 PCM boost remains available on supported decoded-audio routes; VLC boost is shown as unavailable. Protected Easynews streams use Media3 for that video to retain credential-safe redirect handling, without changing the saved engine preference.
- With VLC selected, identified DASH (`.mpd`) and HLS (`.m3u8`) URL paths use Media3 for that session and display a notice. DASH routing follows a reproduced native VLC abort/no-output case on the Sony Android 8 TV; HLS routing is precautionary rather than a reproduced HLS failure. This check uses the URL path suffix only, ignores query parameters and letter case, and does not identify extensionless manifests. It preserves the saved VLC setting for other files and does not establish universal adaptive-stream compatibility.
- On `SVP-DTV15` / Android API 26 only, Media3 prioritizes advertised `OMX.google.h264.decoder` for AVC while retaining every other decoder's fallback order. It excludes only `OMX.MTK.VIDEO.DECODER.VP9` from VP9 choices and adds a soft H.264-before-VP9 preference only when no explicit MIME preference exists. Alternative VP9 decoders/VP9-only sources remain eligible; actual support can retain hardware alternatives, and unsupported AVC cannot block supported VP9. Other devices/API versions, explicit preferences, the selected engine, and VLC remain unchanged. The preceding `7abc58…ee15` public-sample audit confirms actual Google AVC and immediate handoff motion; larger-file and sustained CPU/power/software performance remain untested.
- Incoming Media3 views prime their known video aspect before codec binding and wait until settled view dimensions match the holder's surface frame, avoiding the prior bind-then-resize ordering. Real native layout regressions cover fullscreen, mini, and non-square pixels.

## Optional services

- Playback accounts include TorBox, Real-Debrid, AllDebrid, Premiumize, Offcloud, and experimental Easynews. One selected playback service is used at a time; accounts remain separate.
- Real-Debrid, AllDebrid, Premiumize, and Offcloud accept user-supplied API keys or private tokens and validate them during connection. Easynews uses the user's own member credentials. These account adapters do not copy credentials from the POV add-on. The unmodified VLC dependency includes upstream public playlist client identifiers; see [Privacy](PRIVACY.md).
- Alternate providers retain explicit video-version selection. Their availability checks and transfer behavior depend on each service; a TorBox cache badge is not applied to other services.
- MDBList adds watchlists, own/liked/external lists, and explicit list/history actions using a user API key.
- Simkl adds AUTH V2 device linking, watchlists, saved playback, and custom-list browsing. A registered Simkl AUTH V2 app Client ID is required. Simkl custom lists require its PRO/VIP service tier.
- Automatic MDBList and Simkl playback tracking requires a separate, default-off consent setting for each connected account. It sends identified title/episode information, playback percentage, and start/pause/stop events. Disabling tracking or changing/disconnecting the account clears unsent work. Trakt retains its existing integration.

## Source-list navigation

Down from a long source-list header now enters the first stream and moves through the displayed rows in order; Up follows the reverse order and returns from the first row to the header. The Android 8 baseline had jumped straight to row 60 of 70, landing on a 720p choice. Ordered row navigation replaces the platform's distance-based choice for these lists and is shared by controller and mini-player focus handling.

The current `bd45a3…69e0` APK passed the expanded Sony native fixture: 70 rows, two complete round trips, first/second-row entry from the header, five middle-source openings, 25 middle-file selections, exact Back restoration, every focused card fully visible, and unchanged history. The fixture is offline and synthetic; it does not verify an authenticated provider transfer or video playback.

## Build and validation

The app still requires Android 8 or newer. Compilation uses Android API 36 for the official LibVLC dependency, while target API 35 and minimum API 26 remain unchanged.

The current source-list candidate passes **418 automated tests across 54 suites, with 0 failures, errors, or skipped**. The latest production-code lint run reports **0 errors / 57 warnings**; the subsequent addition was unit-test coverage only. The app and AndroidTest APKs assemble successfully. Its main APK is `bd45a3…69e0`, version 0.3.0-preview / code 6; the full SHA-256 is recorded in [the audit](AUDIT_0.3.0.md). Native source-screen regressions cover 70 rows across all six themes, with and without a synthetic mini-player, bottom-edge return, and exact Back restoration. They use API 26 at 1920 pixels with actual 202-pixel rows and 18-pixel gaps.

The preceding `7abc58…ee15` APK passed the immediate Sony Media3 adaptive-fallback audit on actual Google AVC with one decoder initialization/no releases, mini motion at 223 changed cells/30 new post-baseline frames, and returned fullscreen at 33/29. Tracks, controls, PCM boost/reset, same-player handoffs, 0 ms paused transitions, and history/preferences restoration passed. An independent immediate repetition passed mini 239/26, returned fullscreen 88/25, and reused mini 124/29 with the same decoder instance. No six-second diagnostic delay was used.

Native VLC passed on that preceding APK with fullscreen/mini/returned motion at 161/33, 170/31, and 188/25 changed cells/new pictures, plus two audio/eight subtitle tracks, local SRT/document picker, native selections, paused handoffs at 0 ms, mini options/Return, and one-Right Stop. Picture-baseline waits were 412–1,634 ms. Its Continue Watching fixture and standalone APK v2 signing verification also passed. **These full player checks have not been rerun on bd45.** The current bd45 APK and refreshed source ZIP completed packaging and their exact archive privacy scan with zero detector findings: 484 APK entries, 44,283 DEX strings, and 222 allowlisted source entries. The earlier 7abc / `56aecc…05a0` archive pass remains historical. This is a bounded static check, without image OCR; selected images were reviewed separately.

The earlier 582 APK's Media3 failure remains separately recorded in the audit as the reason for the compatibility changes. The earlier seven-row source fixture is also retained as historical evidence; it did not expose the later 70-row focus jump.

All six reviewed Sony guest Home captures from the `582cec…6273` candidate are available in [the comparison gallery](previews/0.3.0/index.html). They verify the corrected card clipping, complete fourth cards, readable Horizon, and restored appearance preference for that APK. Earlier native picker/focus checks and current visual captures remain separately scoped; neither establishes a later playback-policy build's behavior.

Three reviewed public-sample captures show [player controls](previews/0.3.0/player-controls.png), [audio options](previews/0.3.0/player-audio.png), and [controls hidden](previews/0.3.0/player-hidden.png). Their scope is the preceding `7abc58…ee15` APK, separate from the six Home captures and the current bd45 source-list audit.

New-provider playback, MDBList/Simkl reporting, and online subtitle downloads have not been exercised with real accounts. A successful build or mocked API test does not verify those services, every video format, or other TV models. Playback audits now require changing video pixels after a nonblack baseline as well as decoder progress, so a moving timeline or a black-to-picture transition alone cannot pass as visible motion.

The app-source ZIP and [separate LibVLC source companion](https://github.com/MM-482/pov-android-tv/releases/download/v0.3.0-preview/POV-Android-TV-0.3.0-LibVLC-3.7.7-corresponding-sources.zip) are prepared together for the versioned release. The companion retains the pinned JNI/VLC snapshots, all 20 patches, 65 required contrib archives for 66 logical packages, configuration/checksum records, 292 upstream notice texts and NDK/runtime/cpu-features materials. Linux patch assembly and all four ABI offline contrib fetch targets passed. Offline POV assembly with the original pinned AAR through a local-file dependency passed; no native compilation, modified-engine device run or byte-identical rebuild was performed. See [LibVLC notes](LIBVLC.md) for exact identity and scope.
