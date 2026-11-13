# Cameo desktop → Android feature comparison

Baseline prepared November 2, 2026 from source, before the current parity implementation. Implementation and validation status refreshed November 12, 2026. The baseline tables describe the starting point; the candidate ledger and review notes describe subsequent code and the verified build. Private-service success, receiver output, exact visual matching and general device compatibility remain separate evidence requirements.

## Sources and interpretation

- **Windows:** supplied `Cameo-Windows-0.2.4-Source.zip`, SHA-256 `5b28a3c19194343d87bc5bbdc0ea1afea2eccebb8696aed4e3e97e75a40550b79`, extracted read-only reference at `inspection/cameo-windows-0.2.4-reference/` (**W** below).
- **Mac:** actual original source checkout named `Cameo-Mac-0.2.1-Source`, especially `Sources/POV/` (**M** below). This establishes that source version's behavior; it does not establish whether a newer Mac build exists.
- **Android:** existing `app/src/main/java/dev/pov/tv/` source, 0.3.0-preview/code 6 at the start of this task (**A** below). Existing uncommitted code is the baseline and must be preserved.

Source files, not roadmaps or mockups, establish implementation. A UI button and real adapter path establish an implemented path; they do not establish private-account success. Platform APIs require Android equivalents. Artwork concepts are separate visual targets, not functional evidence.

The Windows README and `W/docs/Windows-Readme.txt` explicitly say the Windows preview omits Apple's AVKit/AirPlay, automatic tracker reporting and Up Next. Mac implements those paths, so feature comparison uses the union of the desktop sources rather than treating Windows omissions as Android requirements to remove working features.

## Largest actionable differences at the baseline

1. **Mobile usability and Cameo identity:** Android's manifest labels the application POV and forces both main and player activities to landscape; `MainActivity.dp()` scales all UI from display width/960. This is a TV application with a launcher entry, not a usable portrait mobile interface.
2. **Local library:** desktop local file/folder import, local watchlist, open-stream action and persistent playlists are absent from the baseline Android application.
3. **Downloads:** desktop explicit quality/source/file/save-path review, byte progress, cancellation and completed-file playback have no Android baseline counterpart.
4. **Service coverage:** desktop has 22 connection types; Android has 6 playback services, 3 trackers and 3 subtitle services. The missing groups are Debrid-Link, put.io, Seedr, AniList, MyAnimeList, Kitsu accounts, Plex, Jellyfin, Emby and WebDAV. Easynews uses a different delivery path on desktop.
5. **Add-ons:** desktop supports independently installed manifests with catalog/source/subtitle resources and metadata provenance; Android baseline has one source endpoint and deliberately rejects header-dependent source entries.
6. **Playback tools:** desktop player has chapters, speed, sleep, detailed playback metrics and more subtitle appearance/timing controls; the player owner is auditing these separately.
7. **State visibility and diagnostics:** desktop has saved playlist operations, sanitized diagnostic export and Mac reporting queue review/discard; Android baseline has playback history and pending sends but no corresponding broad library or diagnostic UI.

## Navigation, library and personal collections

| Capability | Desktop source evidence | Android baseline evidence | Required outcome / adaptation |
|---|---|---|---|
| Featured home and discovery shelves | W `ui/app.js:20`, `ui/mac-shell.js`; M `ConceptShell.swift`, `HomeFeed.swift` | A `MainActivity.kt:367` (`home`), `ConceptHomeRenderer.kt` | Preserve live metadata/artwork, no concept screenshot masquerading as interactive UI |
| Movies and series catalog, search and pagination | W `ui/app.js:37`; W `WebHost.cs:237`; M `CatalogService.swift:60` | A `CatalogApi.kt:9,15,22`; A `MainActivity.kt:949,963` | Present already; keep exact identity checks |
| Genre filtering | W `ui/app.js:37` sends genre; M `CatalogService.swift` | A `CatalogApi.kt` only popular/search; no genre parameter | Add genre browse/filter without changing title identities |
| Previously loaded catalog pages offline | M `CatalogService.swift:51,73,78` uses `CatalogPageCache`; M `CatalogPageCache.swift` | A baseline `CatalogApi` uses live `publicJson` directly | Bounded public metadata cache with explicit stale/offline notice; no claim of downloaded videos |
| Exact season/episode selection | W `ui/app.js:43,47,48`; M `DetailView.swift` | A `MainActivity.kt:1017`, `EpisodeData`; A `AnimeEpisodeCatalog.kt` | Already present; preserve sorted seasons, Specials label and exact source identity |
| Local video picker and playback | W `WebHost.cs` `openFiles`/`play.local`; M `NativeMediaImport.swift:56`, `LibraryView.swift:85` | No baseline video import action; `ExternalSubtitleFiles` imports subtitles only | Android Storage Access Framework file picker, persisted grants, local playable URI |
| Folder import, bounded scan/progress/cancel | M `NativeMediaImport.swift:70`; M `LibraryView.swift:127`; W `NativeActions.cs` | Absent | SAF document tree with cancellation and bounds; mounted/network providers are provider-dependent |
| Drag/drop file import | M `LibraryView.swift:108`; Windows file UI | Absent | Android file/share/open intents and optional drag/drop where practical; touch picker remains primary |
| Open a direct stream | W `ui/app.js` `openStream`; W `WebHost.cs` `openStream` | No baseline main UI action | Explicit URL entry, validation, no durable secret URL/header in collections |
| Local watchlist independent of trackers | W `WebHost.cs:240`; W `ui/app.js:31,32`; M `LibraryStore.swift` | A `MainActivity.kt:988` only connected Trakt watchlist plus MDBList/Simkl actions | Local add/remove needs no external account; remote actions remain separate |
| Continue Watching and resume | W `history.remove`; M `LibraryStore.swift`; connected asset snapshots | A `PlaybackStore.kt:14,26,37,42`; `MainActivity.kt:647` fresh resolve | Present; preserve exact media/account/file keys and fresh URL resolution |
| Remove Continue Watching | W `WebHost.cs:254`; M library/history UI | A `PlaybackStore.kt:51`, `MainActivity.kt:567` | Present; Android hiding is local and intentionally preserves remote watched state |
| Persistent named playlists | W `PlaylistService.cs`; W `WebHost.cs:255`; W `ui/app.js` `playlists` | Absent | Create/rename/delete playlists; durable media references |
| Playlist entries and order | W `PlaylistService.cs`; UI `playlist.add/remove/move` | Absent | Add local/catalog/exact episode/connected files, remove/reorder and choose entry |
| Playlist playback and source boundary | W `Player.cs` playlist controls; `ui/app.js` `openPlaylistSource` | Absent | Advance playable files; catalog entries request a source explicitly, never silent transfer |
| Completed downloads in local library | W `NativeActions.cs` download completion; M download coordinator/views | Absent | Open completed selected document and optionally register as local video |

## Downloads, transport and source selection

| Capability | Desktop source evidence | Android baseline evidence | Required outcome / adaptation |
|---|---|---|---|
| Advertised quality chooser | W `PlaybackChoices.cs`; W `ui/app.js:49` and `chooseQuality`; Windows readme | A `MainActivity.kt:1060,1083` source list only | Optional quality grouping must use actual reported resolution; unknown ≠ Original |
| Source/file list choice | W `PlaybackChoices.cs`; W `NativeActions.cs`; W `ui/app.js` detail sources | A `ProviderHub.prepare`, `providerPrepared`, `MainActivity.kt:1134` | Present exact-file choice; preserve every eligible edition and episode mismatch guard |
| Explicit cloud preparation | W `Api.Prepare`; M `ProviderServices.swift`; W `ui/app.js:65` | A `SourceResolver.kt:84`, `ProviderHub.kt:92` | Already explicit action; never create transfers on metadata navigation |
| Guided save location and explicit download confirmation | W `NativeActions.cs` download action; M `GuidedDownload*` | Absent | SAF create-document destination plus filename/size/source review |
| Progress/cancel/failure state | W `ui/app.js:133–137`, download job events | Absent | Worker download, visible byte/progress state, cancellation and safe failure cleanup |
| No-overwrite file commit | M local download coordinator; W `NativeActions.cs` | Absent | Do not replace an existing user document silently; provider semantics must be surfaced |
| Streaming manifest exclusion | W download path rejects HLS/DASH; M feature matrix + coordinator | No downloader | Reject playlists/web/error documents rather than claim an offline movie |
| Interrupted download restart behavior | W readme explicitly no resume across restart; M queue reports interrupted | No downloader | Honest interrupted state; retry resolves and reviews fresh source |
| Private playback URL/header persistence | W source resolves in memory, scrubbed durable descriptors; M `LibraryStore.swift:26` remote snapshot | A `PlaybackStore` stores keys/identity/progress, not resolved stream URL | Preserve boundary in new library, playlist, job and diagnostic storage |
| Source credential headers | W `Services.cs:145` supports validated proxy headers; M add-on delivery contracts | A `SourceResolver.kt:36` rejects all proxyHeaders; `ProviderPlayback` can carry headers | Add supported header propagation with origin/redirect policy; never discard silently |
| Structured service errors | W `Services.cs:20–23,78–104` status/host/phase/category and sanitization | A `Net.kt` `ApiException`; main toast/dialog generic error | Add useful sanitized operation/status context; preserve HTTP451 refusal distinction |
| Explicit cancellation/account revision | W playback request IDs and connection drafts; M account-scoped snapshots | A `ProviderHub` state/session snapshots; source pages capture revision | Preserve existing guards while extending services/collections |

## Connections: desktop source union vs Android baseline

Desktop service identities are defined in **M `ServiceContracts.swift:3–27`**. Windows verification/browse/resolve adapters live in **W `Services.cs`** and **W `AdvancedServices.cs`**, with connection UI in **W `ui/app.js`** and native connection drafts in **W `WebHost.cs`**. Android baseline credentials are encrypted by **A `data/SecureStore.kt`**, with playback specs in **A `ProviderHub.kt`**.

| Group | Service | Android baseline | Parity action / boundary |
|---|---|---|---|
| Playback | TorBox | `TorBoxApi`, account validation, cloud, cache, prepare/files/playback | Preserve device linking/key setup, account snapshots and no credential forwarding to public indexer |
| Playback | Real-Debrid | `RealDebridProvider` | Preserve manual token, selected-file links and explicit preparation |
| Playback | AllDebrid | `AllDebridProvider` | Preserve current API/auth and exact files |
| Playback | Premiumize | `PremiumizeProvider` | Preserve cloud and directdl choices |
| Playback | Offcloud | `OffcloudProvider` | Use documented transfer ID path; desktop has no invented whole-account history API |
| Playback | Easynews | `EasynewsProvider` web search/playback | Desktop additionally has TLS NNTP account check, NZB file import, yEnc download/cancel; cannot call these equivalent |
| Playback | Debrid-Link | Missing | Add account, seedbox files, exact resolve and explicit transfer preparation |
| Playback | put.io | Missing | Add OAuth-token account, folder/file browse, download resolve, explicit transfer/poll |
| Playback | Seedr | Missing | Desktop uses official WebDAV on eligible plan; no invented private API |
| Tracking | Trakt | `TraktApi`, watchlist, list browse, watched, scrobble, pending queue | Preserve existing integration and exact episode identity; add explicit unwatched if required |
| Tracking | Simkl | `SimklApi`, watchlists/lists, paused progress, watched, optional reports | Add missing explicit remove/unwatched/incremental activity features only with real API contracts |
| Tracking | MDBList | `MdbListApi`, key validation, own/liked/external lists, watchlist/watched/reports | Desktop also supports valid OAuth token where applicable; preserve API-key path |
| Tracking | AniList | Missing account integration; Kitsu discovery is not AniList tracking | Add actual AniList IDs, GraphQL list import and explicit progress/status/score edits |
| Tracking | MyAnimeList | Missing | Actual MAL IDs, list import and explicit progress/status/integer score edits |
| Tracking | Kitsu | Discovery only, no authenticated account adapter | Account/token validation, anime list and progress/status/score edits |
| Subtitles | OpenSubtitles | Existing provider/settings/search/download/player attach | Preserve user's credentials, exact title/episode and quotas |
| Subtitles | SubDL | Existing provider/settings/search/download/player attach | Preserve API key and exact search context |
| Subtitles | SubSource | Existing provider/settings/search/download/player attach | Compare desktop pagination/multi-result file selection |
| Servers | Plex | Missing | Token/server libraries/parts, original file playback; no hosted login/transcoding claims |
| Servers | Jellyfin | Missing | Token/password, reachable server, browse/search, exact media source; supplied Windows path limits each request to 250 items |
| Servers | Emby | Missing | Token/user ID or password, browse/search and original source |
| Servers | WebDAV | Missing | Bounded PROPFIND folder/file browse and origin-scoped in-memory credentials; private HTTP policy |

## Add-ons and metadata provenance

| Capability | Desktop source evidence | Android baseline | Parity action |
|---|---|---|---|
| Multiple installations | M `AddonStore.swift:83,107,123,133,138`; W `WebHost.cs:272–282` | One encrypted `sources.endpoint` in Settings | Install/validate/refresh/enable/remove manifests separately, preserve existing endpoint migration |
| Declared catalogs and extras | M `AddonManifest` resources/extras; W `addon.catalogs`/`addon.catalog` | No custom catalog browser | Only supported type/search/genre/skip extras; retain provider provenance |
| Metadata and non-IMDb identities | M `AddonResource.matches`, catalog/detail resource identity | A `CatalogApi.details` requires IMDb; `PlaybackIdentity` IMDb-only | Keep custom resource namespace separate; never construct guessed IMDb/episode IDs |
| Multiple sources and partial failures | Desktop bounded parallel add-on reads with per-provider errors | Single source read fails as a whole | Bounded fan-out, dedupe, report partial failure without hiding working choices |
| Direct/headered/torrent/external sources | W `Services.cs:145`; Mac add-on stream model | HTTPS direct or hash; headered entries skipped | Carry explicit supported headers; distinguish external link/open action from playable video |
| Subtitle add-on resources | W `WebHost.cs:292`; M subtitle services and declared resources | Not present | Exact title/episode resource lookup, explicit file choice, safe subtitle payload handling |

## Mac-specific improvements and Android equivalents

| Capability | Source / behavior | Android baseline and required outcome |
|---|---|---|
| Up Next in the player | M `UpNextCoordinator.swift:18,48`: near end, actual next episode, don't skip unaired next, related movie by genre, dismiss per session | Android Studio's home Up Next uses Trakt (`MainActivity.kt:426`); this is not the same as in-player end recommendation. Add explicit source opening rather than unselected autoplay |
| Durable reporting review | M `TrackerSyncQueue.swift:18,115,149,157,190`: pending/inFlight/needsReview; retry/discard; rate-limit backoff | A `AdditionalTracking.kt` has account-scoped durable pause/stop and backoff, but no review/discard UI or ambiguity state; `PlaybackStore` has Trakt pending events |
| Automatic reports | Windows explicitly omits; Mac Trakt/Simkl/MDBList opt-in | Android already has Trakt plus opt-in MDBList/Simkl. Do not remove it to mirror Windows omissions |
| Native and compatibility player choices | Mac AVKit + VLCKit; Windows integrated libVLC | Android Media3 + LibVLC already present. Match user-facing choice/recovery/session behavior, not Apple framework names |
| AirPlay | M native route picker or compatibility help; receiver output unverified | Apple route picker cannot be copied into Android. Android casting/route output needs an Android implementation and actual receiver validation; do not expose a fake AirPlay button |
| Separate window and movable Mini across Spaces | Mac AppKit + OS behavior; Windows pop-out + mini | Existing Android in-app mini shares session. Android PiP/window behavior is an OS adaptation, distinct from in-app browsing mini |
| Player tools | M `PlayerToolsView.swift`; W `Player.cs` | Audit owner covers chapters/speed/sleep/details/subtitle timing/appearance; controls must remain usable on TV and touch |
| Independent application icons | M Settings + icon store; W `IconService.cs`, `ui/app.js` icon picker | Baseline Android one static manifest icon. Android launcher-alias/icon switching needs explicit platform implementation; theme selection alone is insufficient |

## Android behavior that should be retained

- Native D-pad source ordering and exact Back focus restoration, including long lists and mini-player state (`TvChoiceList`, `TvFocusController`, source-navigation tests).
- Same-player fullscreen/mini handoff and existing Sony/API26 decoder compatibility policy (`InAppPlaybackSession`, `Media3DecoderPolicy`, `SurfaceReadyAttachment`). Desktop code is not a reason to remove hardware-specific fixes.
- Android document-provider storage semantics and persisted URI grants rather than desktop absolute paths.
- Account revision checks before and after worker requests; no provider lock held during I/O (`ProviderHub`), current TorBox read guard and redacted errors.
- Existing optional Media3/LibVLC selection and supported request headers; no credential-bearing source URL copied into public packages or durable collection records.
- Android 8/API26 minimum unless separately changed and validated. Mobile support requires responsive UI, not merely a `LAUNCHER` manifest category.

## Concrete verification plan

1. Source compare against this baseline, with implemented/partial/missing status updated only after integration.
2. Behavioral unit tests for collection persistence/order, URI validation, exact episode/source selection, API request/response contracts, state changes during I/O, cancel/failure cleanup, download content rejection, diagnostics redaction and migration.
3. Assemble both application and AndroidTest APKs, run existing suites and lint; preserve/review current platform warnings separately.
4. UI captures on defined portrait mobile and landscape TV viewports; compare against approved visual reference PNGs. Generated mockups alone do not prove one-to-one implementation.
5. Physical playback checks on the user-authorized Sony only after a candidate is built, then separately on available mobile hardware/emulator. A Sony test does not establish phone/fold/tablet compatibility.
6. Private service tests require owner's reachable accounts and explicit chosen operations. Mock APIs establish contract behavior, not premium account availability or universal playback.
7. Clean APK/source release scan: no user credentials, account databases, private URLs, test media, personal captures or build machine configuration. Preserve source licenses/notices and original attribution.

No repository upload, community post or external account change is part of this inventory task.

## Candidate implementation ledger

The baseline tables above deliberately remain a record of the starting point. The following implementations are included in **0.4.0-preview / versionCode 7**. The complete automated run passed **688 tests across 88 suites, with 0 failures, 0 errors and 0 skipped**. Android lint completed with **0 errors and 76 warnings**; these warnings remain recorded rather than being described as a warning-free build. The application and AndroidTest APKs assembled successfully. The [versioned audit](AUDIT_0.4.0.md) records the artifact and device-check scopes.

The main APK has SHA-256 `cadfdec6ffef08700a7e582eff8a0fb7d7e70a4cc61db0db26e4f72016e7e9eb`. Standalone v2 signature verification passed, and its development certificate matches the retained earlier Sony application APK. It remains a **debug-signed sideload preview**, with application ID `dev.pov.tv` retained for a signature-compatible update from POV. The versioned audit records each physical-device checkpoint, installed-byte checks, owner-operated folds, preserved-data checks and public playback results separately. Installation checks do not establish private-account playback, every fold/window state, receiver output or a one-to-one visual match.

| Area | Candidate implementation | Evidence boundary |
|---|---|---|
| Local library / local watchlist / saved playlists | `CameoLibraryStore.kt`, MainActivity library and playlist routes | Collection, metadata round-trip, namespace/exact-episode, ordering and preserved-data regressions passed in the complete suite. Local document access still depends on the grants and capabilities supplied by the selected Android document provider. |
| Playlist dispatch | `CameoPlaybackQueue.kt` | Queue/order and unrelated-playback adoption regressions passed. Saved references dispatch to the caller; catalog entries still require explicit source choices rather than a silent transfer or first-source selection. |
| Mobile/TV shell and Cameo identity | `MobileCameoRenderer.kt`, `CameoTvRenderer.kt`, MainActivity device adaptation, manifest/app resources | Five native mobile theme families plus five separate Cameo TV compositions. Only the new Cameo themes are selectable; previous POV choices migrate to their Cameo equivalents without changing account, playback or mobile appearance settings. Geometry, action, migration and actual picker-route regressions cover this behavior. Controlled native renders establish their specified test canvases; they do not establish exact concept matching or physical Z Fold 8 cover/unfold parity. |
| Compact mobile appearance picker | `MobileThemePickerRenderer.kt`, MainActivity appearance route | Responsive portrait-preview grid with visible theme names, selection badges and whole-card first-touch selection. Geometry and real MotionEvent regressions passed. The picker displays reference images; the live application renders separate native controls and current content. |
| Mobile touch, navigation and system-bar spacing | MainActivity, `MobileCameoRenderer.kt`, `MiniPlayerFocusRouter.kt`, source/file/player-option rows | Real touch gestures cover first-touch activation, including mini-player video/caption regions after router setup. Mobile navigation remains intact during mini playback and touch scrolling survives loading. Insets are requested at attachment and refreshed from full-window data, including fold/rotation changes and already-inset legacy parents. Physical phone findings motivated these fixes; exact final-device outcomes are recorded in the versioned audit. |
| Downloads / storage policy / diagnostics | `CameoDownloads.kt`, `DownloadValidation.kt`, `MediaAddressPolicy.kt`, document-picker and diagnostic routes | Controlled tests passed for content rejection, cancellation, no-overwrite guards, redirect/header policy, interrupted-job handling and sanitized metadata. Resolved addresses/headers remain in memory. This does not certify every USB/network document provider, real remote file or premium-account download. |
| Explicit quality grouping | `CameoSourceQuality.kt` | Resolution grouping/order regressions passed. Unknown resolution remains **Quality not provided**; filtering preserves eligible exact file/source choices and does not label unknown media Original. |
| Debrid-Link | `data/DebridLinkProvider.kt`, ProviderHub specs and backend wiring | Account/seedbox/generated-link contracts, bounded browsing, exact fresh file resolution and explicit preparation are covered by passing controlled fixtures. No real account certification. |
| put.io | `data/PutIoProvider.kt`, ProviderHub specs and backend wiring | OAuth-token account, folder/cursor/transfer, exact file resolution and explicit preparation fixtures passed. No real account certification. |
| NNTP NZB downloads | `CameoNzb.kt` | XML/protocol, yEnc/CRC, range/order, cancellation and controlled storage regressions passed in the complete suite. The network path uses TLS NNTP; assembly is staged before writing a new empty user-selected document. A real Easynews NNTP transaction and every SAF provider have not been certified. RAR/PAR2 files are downloads, not an advertised archive repair/extraction feature. |
| Media servers and anime trackers | `data/DesktopServiceHub.kt`, `CameoServiceScreens.kt` | Controlled service/UI fixtures passed for Jellyfin/Emby/Plex/WebDAV/Seedr and AniList/MAL/Kitsu verification, browsing, exact editions/header-bearing playback and explicit anime progress/status/score. Account revisions remain attached through playback, saved references and edits. These tests use fixtures, not real private accounts. |
| Named playback-service accounts | `data/ProviderHub.kt`, SecureStore and provider-account routes | Profile creation, switching/removal, account revision and stale-work fixtures passed. Existing TorBox linking/token changes are reconciled with profile state. Persisted credentials remain encrypted; public artifacts do not include a user's saved accounts. |
| Multiple add-ons | `data/AddonCatalogApi.kt`, `CameoServiceScreens.kt` and add-on routes | Manifest/resource matching, catalogs, exact episodes, headers, subtitles, partial failures and configuration-revision fixtures passed. Install/enable/refresh/remove and custom namespaces are integrated. Disable/re-enable invalidates stale work; unsupported external/open-only actions and resource fan-out limits remain as described below. |
| Public offline metadata and genre browsing | `data/PublicCatalogCache.kt`, CatalogApi and catalog UI | Cache/genre/paging fixtures passed. The public-only cache is bounded to 200 entries, 24 MiB total and 2 MiB each, with a six-hour TTL, seven-day stale fallback and explicit notice. It does not cache private endpoints or make streamed video available offline. |
| Explicit watched/unwatched edits | Trakt, Simkl and MDBList adapters / service screens | Watched true/false and account-scoped controlled fixtures passed. Simkl isolated watchlist removal remains blocked where its API would delete shared history, matching the desktop guard. No live private-history edits are claimed by the test result. |
| Reporting review and in-player Up Next | `AdditionalTracking.kt`, MainActivity queue routes, `CameoUpNext.kt` | Exact pending-event review/discard, account consent/revision, retry/backoff and recommendation identity regressions passed. Existing Trakt and opt-in MDBList/Simkl reporting remain. Up Next opens an explicit source choice; it does not select a stream or transfer automatically. |
| Player tools and saved captions | `PlayerActivity.kt`, `LibVlcPlayer.kt`, playback preference/caption/timer helpers | Speed, sleep timer, chapter/detail/timing/caption paths and caption preservation regressions passed. Controls expose supported operations; software volume boost remains Media3-only. Malformed saved caption indexes cannot overwrite retained captions, and playback can continue with a useful notice. |
| Touch-revealed and held player controls | `PlayerControlVisibility.kt`, PlayerActivity touch forwarding | Real PlayerView/TimeBar regressions passed: a native reveal remains visible at callback +999 ms and hides by +1,001 ms for a 1,000 ms test timeout. Held touch/scrubbing suspends both hide timers; release/cancel renews the interval. Playback refreshes do not pin chrome, and observing a hidden gesture does not reveal it. |
| VLC Chromecast route and Android PiP | `NativeVlcRendererDiscovery.kt`, native driver/player and PlayerActivity routes | Renderer ownership, stale selections and same-player route-state fixtures passed. Explicit discovery is bounded and blocks protected-header sources. No receiver was discovered or confirmed to show video during validation; Chromecast is not Apple's AirPlay. Android system PiP is a separate device capability from Cameo's in-app mini-player. |
| Independent launcher icons | `CameoIcons.kt`, manifest activity aliases and Cameo icon assets | Five independently selectable Cameo launcher icons are integrated separately from themes. Launcher caching and OS behavior still determine when a changed icon appears. |

Independent review corrected metadata serialization loss, add-on namespace collisions, unrelated account/file queue adoption, small hit targets, ambiguous IPv4 notation, download overwrite/cancellation handling, unsupported downloaded content and malformed caption-index replacement. Physical phone review of the earlier candidate additionally exposed first-touch focus consumption, mobile navigation collapse during mini playback, expired control-reveal timing and oversized appearance previews. The current build includes these fixes and the complete suite passes. Final on-device playback, folding, document-provider, PiP and receiver checks must retain their own results in the versioned audit; passing automated checks are not a claim that the app has no remaining bugs.

Dependency recovery investigation was read-only: the original local cache contains the 16 AARs missing from the initial host offline cache. The implementation owner restored them with the online Gradle attempt. All 22 overlapping source/host cached AARs compare byte-for-byte. The poolingcontainer 1.0.0 SHA-1 is `0579fc832cf0e5be08d8d9571850b50d22d9de3c`, exactly matching Google's published checksum; its Gradle directory omits the leading zero, which is not a modification. No cache file was changed by this inventory worker.

## Service review corrections and remaining boundaries

The November 12 source review corrected concrete integration faults before release:

- Server playback now receives only the required media authorization header: `X-Emby-Token` for Jellyfin/Emby, `X-Plex-Token` for Plex, and Basic authorization for WebDAV/Seedr. API-only product and client-identification headers remain on API requests. Fixtures check that the actual player header policy accepts all three outputs.
- Service/add-on configuration reads reject malformed or duplicate stored rows instead of silently discarding them before a later write. Size limits apply before persistence; rejected writes preserve the previous configuration.
- Server/anime browse results retain their account revision, so a detail view opened before account replacement cannot play, download or write anime progress using the replacement account.
- Add-on requests reject stale results even if the add-on was disabled and enabled again while a request ran. Configured add-ons are required when their manifest declares configuration is still needed. Duplicate header names, control characters and transport header overrides are rejected.
- Local HTTP server validation rejects ambiguous numeric host forms such as leading-zero IPv4, shortened IPv4 and integer-address notation. Remote servers require HTTPS; origin and configured-root checks still apply.
- Torrent preparation now uses one identity policy across TorBox, Real-Debrid, AllDebrid, Premiumize, Offcloud, Debrid-Link and put.io. Custom IDs require the original catalog namespace, parent title, media type, exact resource video ID and selected season/episode to match retained request provenance. A stream provider may differ from the metadata owner, but neither can replace that original identity. Plain catalog lookups remain IMDb-only; source/file episode mismatch checks still apply before transfers.

These are deliberately separate from private-account certification. Remaining source/platform boundaries must be retained in release wording:

- Jellyfin/Emby/Plex folder reads follow the supplied Windows implementation's 250-item request limit. This candidate does not establish fully paginated browsing of arbitrarily large server libraries. MyAnimeList and Kitsu account-list adapters have separate bounded paging paths.
- Seedr requires a plan that includes its official WebDAV service. Plex/Jellyfin/Emby use reachable server addresses and original media files; hosted login flows, transcoding and live sources that require opening a server session are not promised.
- AniList/MyAnimeList/Kitsu edits are explicit progress/status/score actions. Connecting one does not automatically report watched playback. Their service IDs remain separate from IMDb; custom series playback still requires exact episode identities from a compatible metadata/source add-on.
- Add-on external/open-only stream actions are not integrated video sources. Unsupported required catalog extras produce an explanation. Add-on source/subtitle reads preserve partial-failure notices, but the candidate source does not establish the desktop's parallel resource fan-out behavior.
- A configured add-on address may contain private configuration and is stored encrypted. It is not a default built into the APK or a credential to copy into screenshots, diagnostics or release materials. Actual playback headers and resolved media addresses are ephemeral.
- The macOS AirPlay route picker and desktop window movement have no literal Android equivalents. Android PiP/in-app mini behavior and any implemented Cast route require their own device/receiver evidence. Visual mockups are implementation references, not proof of rendered or physical-device parity.
