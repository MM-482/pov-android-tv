# Roadmap

The **0.3.0 working update** adds six native themes (Glass, Essential, Premiere, Atelier, Horizon, Studio), embedded VLC, Stop, local Continue Watching removal, local/online subtitles, six playback-service adapters, MDBList, and Simkl. See [its change notes](RELEASE_NOTES_0.3.0.md), [setup guide](INSTALL_0.3.0.md), and [validation record](AUDIT_0.3.0.md). The published release remains 0.2.2; the implementation and candidate tables below describe that earlier release. New authenticated-service validation and the native corresponding-source bundle remain release work.

POV Standalone is an independent Android TV preview made by Mark Mateo, based on the original POV Kodi add-on by [kodifitzwell](https://github.com/kodifitzwell/repo). The list below separates functionality present in **0.2.2-preview** (`versionCode 5`) from future work. It is not a release schedule or a promise of service compatibility.

## Current 0.2.2 preview source

| Area | Implemented scope |
| --- | --- |
| Android TV | Native launcher entry, D-pad navigation, visible focus, stable Back selection/scroll restoration, catalog browsing/search, and one selected season's episodes at a time. |
| Appearance | Cinematic, Editorial, Neon, Cinema, and Dashboard, with a saved device preference. |
| Anime | Separate series/movie/special discovery and search; generic per-video mapping scopes episodes to the selected season/cour; catalog-linked seasons/story parts, movies, OVAs/extras, and spinoffs are grouped separately. Manual matching remains available for incomplete or conflicting records. |
| TorBox | Device-code/API-key connection, torrent/Usenet/web-download cloud libraries, explicit source/video-version choice with stable file IDs and sequential middle-row focus, cache checks, transfer/link resolution, and playback integration. Multiple eligible editions stay visible; a unique exact filename suggests focus. |
| Trakt | User-supplied application Client ID, device-code linking, watchlists/personal lists, watched actions, progress/next episodes, queued updates, and guards against stale account/auth responses. |
| Playback session | One in-app Media3 player shared between fullscreen and mini view; retained browsing root/catalog/scroll/mini state, direct local Now playing navigation, D-pad-reachable mini, and hold-Select/MENU Return/Stop choices. Incoming views wait for an attached, shown, valid surface; stale view/session callbacks are canceled, and outgoing targets are retained while the lifecycle allows. Ten readiness regression cases cover those transitions. Video-surface animations are disabled. Foreground-aware pause/resume; a compact 124 dp bottom controller with title/loading status, timeline, transport, and options. |
| Mini-player navigation | Right stays within the current horizontal row while cards remain, including offscreen cards that need scrolling; at the last card it can enter the mini-player without being diverted to another row. Two additional regression cases cover those boundaries. |
| Playback options | All audio/subtitle tracks exposed by the file, including unknown and unsupported entries with explanations. Automatic selection mode is separate from the indication of the actual selected track. Decoded PCM software gain provides Off/+3/+6/+9/+12 dB with bounded sample clipping and status based on actual processing. Compressed passthrough playback stays available but reports boost unavailable; hardware audio offload is disabled and normal TV volume controls are unchanged. |
| Playback state | Local resume/history, canonical cloud-file resume identity, source-selection debouncing, and explicitly identified movie/episode tracking. |
| Video compatibility | Loaded Media3 support flags pause unsupported/excess-capability video with a safe codec/resolution message and Choose another source. Handled/adaptive video remains allowed. Leaving the error releases the blocked session; it creates no new history/progress or Trakt scrobbles. Eighteen unit cases cover loading, READY, mixed groups, selection, and message privacy. |
| Credits and source | Mark Mateo standalone-app credit, upstream kodifitzwell attribution, GPLv3 source and runtime notices. |

The 0.2.2 revision passed **227 unit tests**, with **0 failures, 0 errors, and 0 skipped**. The debug APK assembled successfully and lint reported **0 errors / 56 warnings**. The combined Sony Android 8 public-sample playback audit passed: 10 audio/four subtitle tracks, 11 fully visible audio-menu rows, manual/Automatic/Off choices, compact 124 dp controller geometry/hiding/loading fixture, 5,120 processed PCM frames at +3 dB then Off, retained browsing root and reused mini view, Right-edge/MENU/hold-Select/Select actions, visible motion and decoder progress in mini/fullscreen, exact paused-player/position handoffs, explicit Stop, and unchanged local history. The final offline source/file-choice fixture passed seven fully visible rows in both D-pad directions, five middle-source openings, 25 middle-file selections, exact Back restoration, and unchanged local history using synthetic choices rather than an authenticated TorBox transfer. Exact packaged-artifact checks and exercised device paths are recorded in the [audit record](AUDIT_0.2.2.md); see also the [change notes](RELEASE_NOTES_0.2.2.md). These results do not validate every TV, codec, HDMI route, or authenticated service. Trakt's user-owned application registration remains a preview limitation. [Privacy](PRIVACY.md) documents service requests, local storage, and backup/transfer exclusions.

A separate Sony episode-navigation check passed eight sequential, fully visible rows, selected S1E9, and restored the exact selection after Back; normal browsing was restored afterward.

One reported frozen-picture/continuing-audio case produced a Sony Android 8 decoder report that 8K HEVC at 7680×4320 exceeded its capabilities. The generic video guard addresses reported unsupported/excess-capability copies, with 18 unit cases, but its post-update error UI has not been physically replayed with that private copy. The supported public-sample handoff audit passed; this does not establish that all freezes have the same cause.

## Near-term verification and polish

- Retain the passing Sony public-sample controller, tracks, PCM, visible-motion, paused-position, mini navigation, source/file focus, and episode/Back regressions. Repeat the relevant checks after further source changes. Extend coverage to additional TVs, all theme layouts, codecs, HDR, and HDMI routes rather than treating one TV/sample as universal certification.
- Exercise authorized TorBox playback, ready/pending transfers, explicit multi-file choice, rapid source selection, seeking, canonical cloud resume, and full/mini player handoffs. Preserve the passing offline source/file focus regression separately from live service results.
- Verify Trakt list retrieval, watched/progress synchronization, refresh/disconnect/account replacement, cancellation during in-flight requests, and offline retries with an authorized account.
- Broaden anime coverage beyond the public-data regression examples: Attack on Titan parts/films/OVAs, Frieren, My Hero Academia season 2, and Your Name. Continue rejecting incomplete mappings rather than claiming all provider records are correct.
- Broaden software-gain checks beyond the successful Sony decoded-PCM +3 dB/Off exercise, including other gain steps, compressed passthrough status, and audible behavior on authorized HDMI/audio routes. Hardware offload remains disabled and normal TV volume controls are unchanged.
- Validate the video guard with unsupported and handled copies, preserving supported HEVC/4K/adaptive playback and confirming a blocked source is released without new history or Trakt progress. Continue investigating other freeze causes through device evidence.
- Broaden Android TV, codec, HDR, subtitle, and device compatibility checks. The in-app mini view does not establish background playback or system picture-in-picture support.
- Keep exact packaged APK/source ZIP privacy checks, hashes, signing/update identity, and validation results tied together in the audit record. Approved actual preview scope is three public-sample player captures and a reviewed Cinematic guest home. The other four actual theme home captures are still outstanding; the five original theme PNGs remain explicitly labeled generated reference concepts.

## Candidate debrid services

TorBox is the current implemented service. The following are **candidates for future investigation**, not options available in this preview:

| Candidate | Work needed before it can be called supported |
| --- | --- |
| Real-Debrid | Review current official integration requirements, design an independent account adapter, implement supported browsing/link operations, and validate with an authorized test account. |
| AllDebrid | Review current official integration requirements, implement service-specific account/source/file behavior, and validate errors, readiness, and playback. |
| Premiumize | Review current official integration requirements, design the appropriate cloud/source adapter, and validate authorized account and playback workflows. |

No API availability, approval, feature equivalence, delivery date, or completed integration is claimed for those services. Adding a service requires its own implementation and testing; a familiar menu label alone would not establish support.

## Further product work

- Simplify public Trakt onboarding if an appropriate project-owned application registration and distribution approach is approved; do not copy another app's registration or embed a client secret.
- Improve provider configuration and diagnostics without exposing account credentials or implementation details in normal browsing.
- Evaluate broader catalog/filter/list features, subtitle workflows, accessibility, and download-management controls based on tested user needs.
- Establish an owner-controlled production signing process and a clear update channel before treating preview APKs as production releases.

The preview does not aim to reproduce every Kodi skin setting, upstream provider, external add-on, or download action at once. New scope should be described and verified before it is announced as available.

## Repository and release boundary

Public source contains buildable app code, documentation, license/credits, and approved preview images. It must not include login information, embedded service credentials, private account/device evidence, inspection downloads, extracted upstream bundles, SDK/JDK/Gradle installations, build caches, or signing material.

Publication of the 0.2.2 preview has been approved for [MM-482/pov-android-tv](https://github.com/MM-482/pov-android-tv) and the [v0.2.2-preview release](https://github.com/MM-482/pov-android-tv/releases/tag/v0.2.2-preview). The APK remains a debug-signed sideload preview. Broader product work and validation listed above remain separate from this approved publication.

