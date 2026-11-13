# Cameo Android roadmap

The current local candidate is **0.4.0-preview / code 7**. It adds mobile/foldable support and portable features from the supplied Windows 0.2.4 and available Mac 0.2.1 source while retaining Android TV. See [current changes](RELEASE_NOTES_0.4.0.md), [setup](INSTALL_0.4.0.md), [source comparison](CAMEO_DESKTOP_ANDROID_PARITY.md) and [validation](AUDIT_0.4.0.md). Earlier 0.3.0 evidence remains historical. A local candidate is separate from a published release.

## Available candidate features

Native five-theme mobile and TV interfaces with automatic migration of prior POV themes, icon selection, local files/folders/watchlist/playlists, explicit downloads, expanded cloud services, media servers, anime account lists, multiple compatible add-ons, genre browsing, public metadata cache, reporting review, diagnostic export and expanded player tools are implemented. Existing Trakt, MDBList, Simkl, dedicated anime metadata and source-navigation protections remain. Details and service boundaries are in the change notes.

## Verification before broader release claims

- Additional fold/window states, local-document playback, subtitle import and Android system PiP on the phone. The specific owner-operated form/fold and public playback checks in the current audit have passed.
- Receiver output for VLC Chromecast, including real picture/audio, seeking and return to local output. Source/ownership tests alone cannot prove receiver success.
- Owner-selected real-account checks for each additional provider, server, anime tracker, reporting operation and subtitle service. Controlled API fixtures do not establish account entitlement or current remote availability.
- Side-by-side concept/native appearance review on actual device hardware, including image outlines, system bars, text scaling and long/translated titles. Do not claim pixel equivalence from generated references.

## Possible later improvements

- Miniature caption/footer spacing in phone landscape, where embedded subtitle lines can overlap the preview label.
- Server libraries beyond the source-compatible 250-item folder request limit.
- Additional compatible catalog extras and external/open-only add-on actions.
- Parallel add-on reads with visible partial failures and bounded cancellation.
- A deliberate Android background-transfer design and resumable downloads, if added and tested later.
- Archive repair/extraction, where practical; current NZB assembly does not include those tools.
- Additional platform/service capabilities supported by documented APIs and tested accounts.

These are directions to evaluate, not a release schedule or compatibility promise. Public upload and community announcements require a separate reviewed release artifact.
