# Cameo Android 0.4.0-preview

This update renames the standalone Android app from POV to **Cameo** and adds native mobile/foldable support alongside Android TV. The Android package remains `dev.pov.tv` for compatible in-place updates. Original POV creator credit is retained; the standalone adaptation is made by Mark Mateo.

The feature comparison uses the supplied Windows 0.2.4 source and the available Mac 0.2.1 source. It preserves Android's existing Trakt integration, anime identity checks, remote source ordering and same-session mini-player.

## Added

- Five native Cameo mobile themes and five corresponding TV layouts. The six prior POV TV themes are removed from Appearance, with automatic migration for existing selections. Five independent Cameo launcher icons.
- Local file/folder import, shared-video import, cancellable scanning, direct stream opening, a local watchlist and persistent ordered playlists.
- Explicit direct-file downloads with destination review, progress/cancel/interrupted state and completed-video playback. Safe rejection of nonvideo responses and nonempty destinations.
- Debrid-Link and put.io playback adapters, Seedr WebDAV, named playback-account profiles, and Plex/Jellyfin/Emby/WebDAV libraries.
- AniList/MyAnimeList/Kitsu account lists and explicit progress/status/score edits. Trakt, MDBList and Simkl remain independent.
- Multiple compatible add-on catalogs, source and subtitle resources, exact custom identities, supported request headers and partial-failure notices.
- Genre browsing, a bounded public metadata cache, sanitized diagnostic export, and pending reporting review/discard.
- Player speed, sleep, chapters where present, details, subtitle appearance/timing and remembered external captions; explicit Up Next and Android PiP.
- Explicit VLC Chromecast discovery/selection on the current compatible session, with bounded discovery and return-to-device action. Real receiver testing is separate.

## Reliability work

Account-scoped stale-result checks protect server files, anime edits, source resolution and add-on changes. Malformed saved lists receive a preserved-data error. Source/file rows keep remote navigation order and every eligible edition. Playback URLs and authorization headers remain ephemeral; public packages exclude account files, private signing material and local machine configuration.

Physical phone review also identified and repaired touch behavior inherited from the TV interface: the first tap activates buttons and source/file/player-option rows; the five mobile navigation entries remain available during mini playback; loading completion preserves touch scrolling; and tapping hidden player controls starts a fresh idle interval. Held touches and timeline scrubbing retain controls until release. Appearance uses compact responsive portrait-preview tiles with clear names and selection indicators. Unsaved form values and input focus survive a fold; system-bar padding is committed after the active layout pass so the actual header and navigation move into the safe area. The owner-operated fold test passed on the release candidate. TV Appearance now contains only the five new Cameo themes.

## Known boundaries

On the tested phone in landscape, embedded subtitles can partly overlap the miniature player's title/footer. Fullscreen captions remain available; miniature caption/footer spacing remains a visual polish item.

This remains a preview. Private service/plan success, every decoder/device, physical Z Fold behavior and receiver playback are not established by controlled fixtures. Server folder reads follow the desktop's 250-item request limit. Seedr requires WebDAV entitlement. Downloads do not resume after process death, save HLS/DASH as complete files, or extract/repair archives. External-only add-on links and unsupported required extras are not integrated playback sources. Anime tracker progress edits are explicit. Simkl watchlist removal is blocked where it would erase shared history. Android Cast/PiP are platform adaptations, not macOS AirPlay/window behavior.

See [the desktop comparison](CAMEO_DESKTOP_ANDROID_PARITY.md) and [the validation record](AUDIT_0.4.0.md) for the exact tested artifact and limits. Preparing this candidate does not publish it to GitHub or Reddit.
