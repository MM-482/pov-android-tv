# Cameo Android 0.4.0-preview: feature mapping and boundaries

This record describes the Android source for **0.4.0-preview / versionCode 7**. It compares the supplied **Cameo Windows 0.2.4** source companion with the Android implementation built on the public Android TV **0.3.0-preview** baseline, commit `1ecffc5b68962ac84e4bd965ff36005ede2aa641`. The regular build uses `dev.pov.tv`; the side-by-side candidate uses `dev.pov.tv.cameopreview` and the launcher label **Cameo Preview**.

“Implemented” below means the behavior has an Android code path and relevant automated coverage where described. It does not certify a live subscription, server, codec, display, or device.

## Reference scope

The Windows companion identifies its version in `README-Windows.md` and describes its features in `docs/Windows-Readme.txt`. The comparison used its actual `src/Services.cs`, `AdvancedServices.cs`, `NativeActions.cs`, `PlaylistService.cs`, `Easynews.cs`, `Player.cs`, `PlayerKeyboardInput.cs`, `IconService.cs`, `Diagnostics.cs`, and local `ui/` source.

The Windows documentation describes inheritance from Cameo Mac 0.2.1. **The Mac application source itself was not supplied for this Android implementation.** Windows descriptions of Mac behavior do not establish direct Mac parity. The original **November 2 concept images were also unavailable**. The five Android appearances use native controls and new palettes; the retained October Android TV galleries are historical references, not evidence of a match to the missing images.

Android keeps its native view hierarchy, Media3 integration, remote navigation, Android document access, and private storage. It does not embed the Windows WebView2 UI, Windows binaries, or Apple frameworks.

## Feature mapping

| Windows 0.2.4 feature and reference | Android 0.4.0 implementation | Scope |
| --- | --- | --- |
| Five themes and independent icon selection: `ui/`, `IconService.cs` | [ThemeSpec](../app/src/main/java/dev/pov/tv/ThemeSpec.kt), [mobile shell](../app/src/main/java/dev/pov/tv/MobileShellRenderer.kt), [TV shell](../app/src/main/java/dev/pov/tv/ConceptShellRenderer.kt), [CameoIcons](../app/src/main/java/dev/pov/tv/CameoIcons.kt) | Glass, Cinema, Slate, Pearl, Aurora; five independent native launcher icons and TV banners. The layout adapts to touch or TV. |
| Local files/folders, watchlist, and saved progress: `NativeActions.cs`, `Services.cs` | [LocalLibraryImporter](../app/src/main/java/dev/pov/tv/LocalLibraryImporter.kt), [CameoLibraryStore](../app/src/main/java/dev/pov/tv/CameoLibraryStore.kt), [feature UI](../app/src/main/java/dev/pov/tv/CameoFeatures.kt), existing [PlaybackStore](../app/src/main/java/dev/pov/tv/PlaybackStore.kt) | Explicit Android document selection, persisted access where supported, local search/paging, device watchlist, and saved media references. Removing a library entry leaves its file intact. |
| Saved playlists: `PlaylistService.cs` | [CameoLibraryStore](../app/src/main/java/dev/pov/tv/CameoLibraryStore.kt), [CameoFeatures](../app/src/main/java/dev/pov/tv/CameoFeatures.kt), [shared session](../app/src/main/java/dev/pov/tv/InAppPlaybackSession.kt) | Create, rename, delete, add/remove/reorder, play from an entry, previous/next, and automatic advancement for playable files. Catalog entries retain explicit source selection. |
| Independently verified connections: `Services.cs`, `AdvancedServices.cs` | [CameoConnections](../app/src/main/java/dev/pov/tv/data/CameoConnections.kt), [media servers](../app/src/main/java/dev/pov/tv/data/CameoMediaServers.kt), [anime trackers](../app/src/main/java/dev/pov/tv/data/CameoAnimeTrackers.kt), [manual tracker profiles](../app/src/main/java/dev/pov/tv/data/CameoManualTrackers.kt) | All 22 service types have independent saved-account profiles. Tracker/subtitle actions carry the chosen account ID; legacy automatic-tracking connections remain separate. Service constraints appear below. |
| Movies, shows, details, and exact anime episodes: `Services.cs`, `AnimeCatalog.cs`, `PlaybackChoices.cs` | [CatalogApi](../app/src/main/java/dev/pov/tv/data/CatalogApi.kt), [AnimeCatalogApi](../app/src/main/java/dev/pov/tv/data/AnimeCatalogApi.kt), [MainActivity](../app/src/main/java/dev/pov/tv/MainActivity.kt) | Public discovery, selected-season episodes, explicit source/file choice, and retained exact IDs. Uncertain anime records keep manual matching; titles are not merged solely by name. |
| Quality/source choice and transfer preparation: `PlaybackChoices.cs`, `AdvancedServices.cs` | [CameoFeatures](../app/src/main/java/dev/pov/tv/CameoFeatures.kt), [ProviderHub](../app/src/main/java/dev/pov/tv/data/ProviderHub.kt), provider adapters | Quality filters reflect source descriptions. Exact files remain selectable. Transfer creation follows an explicit selection; supported providers wait on that transfer with bounded polling. No on-device P2P client is added. |
| Installed catalogs, sources, metadata, and subtitles: `Services.cs`, `AdvancedServices.cs` | [AddonRepository](../app/src/main/java/dev/pov/tv/data/AddonRepository.kt), [subtitle integration](../app/src/main/java/dev/pov/tv/subtitles/SubtitleRepository.kt) | Install/enable/disable/remove HTTPS or validated local HTTP manifests; supported search, genre, paging, exact `videos.id`, stream `fileIdx`/request headers, and enabled add-on subtitles. `stremio://` addresses normalize to HTTPS. |
| Save selected videos and track downloads: `NativeActions.cs`, `Services.cs` | [CameoDownloads](../app/src/main/java/dev/pov/tv/CameoDownloads.kt), [download UI](../app/src/main/java/dev/pov/tv/CameoFeatures.kt) | Explicit destination, progress, cancellation, completion/interruption history, and local playback of completed files. Requires a living app process and supported document provider. |
| Easynews NZB import/download: `Easynews.cs` | [EasynewsNzb](../app/src/main/java/dev/pov/tv/data/EasynewsNzb.kt), [CameoDownloads](../app/src/main/java/dev/pov/tv/CameoDownloads.kt) | Select a local NZB, one contained file, and a destination; fixed-host NNTP TLS, bounded XML parsing, clean names, and yEnc CRC/size/order verification. |
| Integrated playback, mini-player, and saved queue: `Player.cs`, `WebHost.cs` | [InAppPlaybackSession](../app/src/main/java/dev/pov/tv/InAppPlaybackSession.kt), [PlayerActivity](../app/src/main/java/dev/pov/tv/PlayerActivity.kt) | Fullscreen and in-app mini-player use the same session. Android fullscreen/touch/DPAD replace desktop pop-out/window controls. |
| Speed, sleep, chapters, caption preferences, and details: `Player.cs` | [PlaybackAdvancedOptions](../app/src/main/java/dev/pov/tv/PlaybackAdvancedOptions.kt), [PlayerActivity](../app/src/main/java/dev/pov/tv/PlayerActivity.kt), [LibVlcPlayer](../app/src/main/java/dev/pov/tv/LibVlcPlayer.kt) | Speed 0.5–2×; sleep 15/30/45/60/90 minutes; size/color/background/position defaults. Chapters and subtitle timing require VLC; the UI explains unavailable controls. |
| Direct network streams: `NativeActions.cs`, `WebHost.cs` | [CameoFeatures](../app/src/main/java/dev/pov/tv/CameoFeatures.kt) | Settings accepts an HTTPS or validated local-server URL for explicit playback or a supported file download. It does not persist the resolved media address as an account/library descriptor. |
| Troubleshooting report: `Diagnostics.cs` | [CameoDiagnostics](../app/src/main/java/dev/pov/tv/CameoDiagnostics.kt), [report UI/export](../app/src/main/java/dev/pov/tv/CameoFeatures.kt) | Review offline app/player facts; optionally check fixed public endpoints; save through the document picker. Secrets, media identity, private URLs/paths, response bodies, and raw exception text are excluded. |

## Connections and service-specific limits

The saved-account interface stores multiple independent accounts for all 22 service types. Changing account/server credentials creates a fresh connection identity; changing only a display name preserves it. Old saved files keep their original account/file/edition IDs. Explicit Trakt/Simkl/MDBList operations and subtitle searches/downloads receive the selected connection ID. These profiles do not switch the credentials of the existing automatic playback reporters. The original Android connections remain accessible separately.

| Group | Services | Implemented behavior and boundaries |
| --- | --- | --- |
| Playback providers | TorBox, Real-Debrid, AllDebrid, Premiumize, Offcloud, Debrid-Link, put.io | Account verification, cloud/file operations supported by each adapter, explicit source preparation, and exact-file resolution. New saved Premiumize accounts use durable cloud transfers; the earlier direct-download behavior remains for legacy accounts. |
| Easynews | Easynews | Saved account validation uses NNTP over TLS. NZB download uses the newsreader path. Web search/playback still requires an Easynews plan with web access. |
| Media servers | Seedr WebDAV, Jellyfin, Emby, Plex, WebDAV | Browse folders and resolve stored video files/editions. Seedr uses its WebDAV interface. Jellyfin/Emby retain the selected media-source ID; Plex retains the selected stored media part. |
| Anime trackers | AniList, MyAnimeList, Kitsu | Read supported account lists and explicitly edit progress, status, and score. No automatic playback reporting or OAuth refresh is added for these three services. Cross-service IDs are not guessed. |
| Tracking/list profiles and retained connections | Trakt, Simkl, MDBList | Independent saved profiles provide explicit list/watchlist/watched operations. Existing linking, identification rules, and optional automatic reporting remain separate. MDBList/Simkl automatic reporting is off by default and requires separate consent. |
| Subtitle profiles and retained connections | OpenSubtitles, SubDL, SubSource | Independent saved accounts provide explicit account-bound search/download. Existing subtitle credentials, local import, and installed add-on results remain available. |

Additional service boundaries:

- Offcloud opens a known request ID; the app does not claim an account-wide Offcloud cloud listing.
- Real-Debrid includes torrent browsing and generated-download history; the chosen history item is re-resolved before playback rather than persisting its temporary URL.
- Plex browsing currently requests the first 250 rows per folder, a limit shared with the supplied Windows source. Other server/tracker operations use their implemented bounded paging; this is not a guarantee of unbounded library traversal.
- Media-server playback covers stored files. Negotiated transcoding, live sources requiring an opening session, casting, and server playback-session reporting are not implemented.
- Source quality comes from the provider's metadata. Unknown resolution is not certified as original quality, and a displayed resolution does not certify decodability.
- Playback links are resolved when needed. Expired URLs, removed files, changed credentials, provider quotas, and account plan restrictions require a fresh action or connection.
- Neither saving an account nor passing a mocked API test establishes that every paid account or server configuration works.

## Android capabilities retained beyond the Windows preview

The Windows reference explicitly identifies automatic tracker playback reporting and Up Next as absent from that preview. Android retains its existing identified Trakt reporting, retry queue, and account-bound Up Next reads, together with separately consented MDBList/Simkl reporting. Reading Up Next does not mark episodes watched.

Other retained Android capabilities include:

- Media3 **and** embedded LibVLC, with recognized adaptive/protected-request routing and the existing device-specific compatibility policy.
- Ordered DPAD navigation for long source and file lists, visible focus, exact Back restoration, and remote mini-player access.
- Local Continue Watching removal that preserves resume data and external watched history; playing a hidden title again restores its eligibility.
- Local subtitle import, preferred-language selection, and the three existing online subtitle providers.
- Media3 decoded-PCM gain options on supported output routes. VLC and passthrough routes report this boost as unavailable.
- Exact episode selection, separate anime films/specials/related works, and manual catalog matching when metadata is incomplete.

The earlier Sony playback and focus audits remain historical evidence for their recorded 0.3.0 APKs. They have not been repeated on this 0.4.0 candidate.

## Phone, foldable, and TV behavior

[AdaptiveLayout](../app/src/main/java/dev/pov/tv/AdaptiveLayout.kt) uses current configuration dimensions in dp and the Android television UI mode. A large screen alone does not select the TV layout.

Touch windows below 600dp use bottom navigation; windows at least 600dp wide use an 88dp rail. The feature can split horizontally when the remaining content is wide enough. Text uses sp, cards and source rows wrap, and core touch actions have usable native touch sizes. The shell, title pages, anime grid, source/file lists, and mini-player avoid the old TV-only width/960 scaling.

Main and Player activities handle size/orientation changes without replacing the shared player solely because the window changes. Main also handles density and font-scale changes in place, keeping the detailed browsing route when a fold changes display density. The navigation callback, Back stack, selected season, and scroll are retained during the handled resize path. This does not claim full restoration of every nested screen after Android kills the process.

The physical Galaxy Z Fold 8 and physical TVs have not been tested with this candidate. The implementation is based on window size, without assumed Fold 8 screen specifications. The optional native-render fixture uses synthetic data at 360dp and 720dp; its PNGs are labelled **SIMULATED NATIVE LAYOUT**, not device captures.

## Downloads and local storage

Video/folder import is scoped to Android's document picker. Folder scans are bounded at 50,000 videos and 80 levels. The local library stores references rather than copying imported media. A provider that removes or revokes document access can make a saved reference unavailable. Cameo does not request broad storage access or add its own SMB browser.

The device watchlist supports up to 5,000 saved entries. Playlists support up to 200 lists and 1,000 entries total. Catalog, episode, account, and service-file identities are retained without persisting resolved playback credentials. These are Android stores; no Windows/Mac library-file import or cross-device synchronization is claimed.

Downloads use two workers with at most eight active/queued jobs and retain up to 100 history entries. Data first lands in private cache, then the completed file is exported to the selected new destination. A nonempty destination is refused. Storage providers must report and support the required file operations; a provider with unknown size or incompatible descriptors may be rejected. Cancellation after export begins attempts to remove the incomplete exported document.

There is no permanent download service, reboot resume, or byte-range continuation of interrupted jobs. HLS/DASH offline packaging is not implemented. Easynews NZB transfers support neither RAR extraction nor PAR2 repair. NZB parsing is limited to 8 MiB, 1,000 files, and 100,000 segments; article and decoded-file limits are 16 MiB and 64 GiB respectively. A missing or corrupt segment fails the selected file rather than reporting a completed download.

## Data and network behavior

The side-by-side candidate has fresh, separate app storage and does not alter the existing regular installation. Its accounts and library must be set up separately; it is not an automatic migration/import tool. Updating the regular `dev.pov.tv` installation in place requires the original signing certificate/private key. An unrelated debug signature cannot update that package while retaining its installed data.

Account credentials and configured add-on addresses are stored through [SecureStore](../app/src/main/java/dev/pov/tv/data/SecureStore.kt), backed by Android Keystore and existing backup exclusions. Private library/watchlist/playlist data, download metadata, local history, and some existing retry data are app-private preferences rather than universally encrypted storage. Windows' encrypt-everything data model is not claimed for Android.

New saved service descriptors contain durable account/file IDs, with no resolved media URL, token, password, or authorization-header field. Resolved playback/download requests stay in memory. Removing a connection does not redirect its old saved entries to a different account. Downloaded videos are written to the location the user chooses and follow that location's access rules.

[PublicMetadataCache](../app/src/main/java/dev/pov/tv/data/PublicMetadataCache.kt) is restricted to allowlisted public Cinemeta/Kitsu requests. It stores at most 60 entries, each capped at 8 MiB, with a six-hour fresh period and a seven-day stale fallback. Account, server, source, and configured add-on requests do not enter this cache. Cached metadata is not an offline media library.

Public APIs and public artwork use HTTPS. User-configured media servers and add-on manifests can use HTTP for validated local/private addresses; HTTP stream/subtitle resources advertised by a local add-on stay on its configured origin. LAN HTTP does not provide TLS encryption. Protected requests use origin-scoped headers, explicit redirect handling, and local-address validation/pinning where required. This explains the manifest's cleartext allowance without claiming that all network traffic is encrypted.

Cameo adds no developer-operated account, advertising, or automatic telemetry upload. The retained [privacy document](PRIVACY.md) describes the earlier Android stores and integrations; this section records the added 0.4.0 flows.

Troubleshooting starts with an offline report. The separate public-connectivity action sends credential-free requests only to fixed public endpoints; it does not test saved accounts or media URLs. The user can review the report and choose a new document destination. No report is uploaded automatically, and public reachability is not an account or playback test.

## Verification and remaining differences

Automated tests exercise public/synthetic data and fake transports. Coverage includes window routing, theme migration, independent icon selection, real native mobile/TV view trees, navigation/session retention across resize, saved descriptors/playlists, service contracts, redirects, download failure/cancellation, yEnc integrity, and advanced playback options. Exact final counts and APK/source digests must be taken from the completed validation record for that candidate.

The following remain outside current verification or implementation:

- Live paid-provider, media-server, tracker-write, subtitle-account, and Easynews NNTP validation with user accounts.
- Physical Fold 8, physical TV, sustained decoder performance, HDR, HDMI/audio routes, and audible gain validation for this build.
- A direct audit of Mac source, or pixel comparison against the absent November 2 concept originals.
- Windows desktop window chrome, freely detached player windows, and Windows-specific keyboard shortcuts; Android uses its native fullscreen, touch, and remote interactions.
- Apple AVKit/AirPlay, casting, system picture-in-picture, and a permanent background playback/sync service.
- Universal add-on compatibility, DRM/license acquisition, arbitrary browser playback, complete offline catalogs, or automatic repair of broken media archives.

The historical setup, release, audit, design, and licensing documents remain unchanged. Their version-specific claims must not be presented as validation of this preview.

