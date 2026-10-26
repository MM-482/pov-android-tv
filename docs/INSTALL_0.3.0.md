# Installing and configuring the 0.3.0 working preview

This guide describes the new working build. Validation is recorded in [the audit](AUDIT_0.3.0.md); the published GitHub preview is still 0.2.2 until a separate release is made.

## Install or update

Use an Android TV or Google TV running Android 8 or newer. Install the APK through your usual sideload method. For an existing POV installation, use an APK signed with the same key and install it as an update. That preserves app preferences and account data. Do not uninstall the app first unless you intend to clear its data. The current local preview uses the same retained local debug key as the previous Sony installation; an independently built APK may use a different signing key.

## Choose an appearance

Open **Settings → Appearance** and select one of the six designs. The selection is saved on this device and applies immediately to the native interface.

| Theme | Layout and style |
| --- | --- |
| Glass | A floating dark glass-style navigation rail, expansive artwork, white actions, and soft white focus. |
| Essential | A fixed dark rail, cyan actions and focus, and compact landscape watching cards. |
| Premiere | Immersive dark artwork, large serif feature titles, a quiet rail, and warm gold focus. |
| Atelier | A warm ivory canvas, horizontal navigation, a split text/artwork feature, and dark focus. |
| Horizon | Horizontal navigation, wide artwork, a cyan/violet action treatment, and fine luminous focus. |
| Studio | A large watching feature, an adjacent episode column, and a bottom row of browse shortcuts. |

These are working layouts with remote-selectable navigation, title actions, and cards. The picker uses the approved generated concept images with fictional sample titles. Home loads actual catalog artwork, available titles, and your own saved progress; those change with the content. On Android 8, glass depth uses tinted surfaces, gradients, and borders rather than a live background-blur effect. Inter and Cormorant Garamond are bundled locally under the SIL Open Font License; no Google Fonts download is needed at runtime. Their source attribution and complete licenses are included in the app assets.

Glass is the fresh-install default. An existing theme preference migrates to its closest new design: Cinematic → Essential, Editorial → Atelier, Neon → Horizon, Cinema → Premiere, and Dashboard → Studio. Changing the appearance preserves playback preferences, account connections, and viewing progress.

All six layouts have offline native UI regression coverage. The current `bd45a3…69e0` source-list candidate passes 418 automated tests across 54 suites, with no failures, errors, or skips; the latest production-code lint run reports 0 errors / 57 warnings. Six reviewed Sony guest Home images from the preceding `582cec…6273` candidate are available in [the comparison gallery](previews/0.3.0/index.html), after the card-viewport correction. The pictures retain that APK's appearance scope. Full Media3/VLC checks belong to the preceding `7abc58…ee15` APK; the current candidate's expanded source-list audit and fresh signature/update checks passed separately. Packaging/privacy status and exact identities are tracked in [the audit](AUDIT_0.3.0.md).

## Choose the player

Open **Settings → Playback → Playback engine** and choose **Media3** or **VLC**. The choice applies to the next video; it does not restart the current one. The VLC engine is inside POV, so a separate VLC app is unnecessary. Media3 remains the default. Protected Easynews playback uses Media3 for that session to avoid sending credentials to a redirected, unrelated host. The saved engine preference remains unchanged.

When VLC is selected, recognized DASH (`.mpd`) and HLS (`.m3u8`) URL paths use Media3 for that session, with an on-screen notice. The DASH route follows a reproduced native VLC abort/no-output case on the Sony Android 8 TV. The HLS route is a precaution for the same adaptive-playback path; an HLS failure was not reproduced. Detection uses only the URL path suffix, ignoring query parameters and letter case. An extensionless manifest or an unrelated suffix is not identified by this rule, so this is not a guarantee for every adaptive stream. The saved VLC preference remains available for other files.

The bottom controller includes a compact square **Stop** between Play/Pause and Forward; one Right press from Play/Pause reaches it. Back returns to browsing with the same session in the mini-player. Select the mini-player to return to fullscreen. While browsing, **Menu** opens **Return to video** and **Stop playback**; hold Select on the mini-player if the TV reserves Menu for system settings. **Volume boost** continues to use the existing Media3 decoded-PCM path; VLC reports boost as unavailable rather than claiming gain it has not applied. File/device capabilities still determine which tracks and formats play.

The preceding `7abc58…ee15` Sony VLC public-sample check retained the player and paused position across these transitions, with visible video motion after each new target became nonblack. A picture can take a brief moment to appear: measured baseline waits were about 0.4 to 1.6 seconds in that run. These measurements do not guarantee the same delay for other sources or TVs, and the full player paths have not been rerun on bd45.

On the exact `SVP-DTV15` device at Android API 26, Media3 gives advertised `OMX.google.h264.decoder` priority for AVC, retains other decoders in their fallback order, excludes only `OMX.MTK.VIDEO.DECODER.VP9` from VP9 choices, and softly prefers H.264 before VP9 when no explicit MIME preference was saved. Other VP9 decoders remain eligible, including for VP9-only media. Actual format support may use a hardware alternative; unsupported AVC cannot block supported VP9, and a source can still fail if no remaining decoder supports it. The rule leaves other devices/API versions, explicit MIME preferences, the saved engine, and VLC unchanged.

On `7abc58…ee15`, the Sony public sample passed immediate Media3 fullscreen/mini/Return motion and an independent reused-mini repetition on actual Google AVC with the same decoder instance and exact paused positions. Native VLC, Continue Watching removal, and standalone signature checks also passed on that APK. Its APK and `56aecc…05a0` app-source ZIP completed the archive privacy scan with zero detector findings. The current bd45 APK and refreshed source ZIP have also completed packaging and their exact scan with zero detector findings: 484 APK entries, 44,283 DEX strings, and 222 allowlisted source entries. Static scanning does not inspect image contents; selected screenshots were reviewed separately. Larger files and sustained software CPU/power/performance are untested; the sample results do not establish every source or format.

## Choose a source or file

Use Up/Down to follow the displayed list order. Down from the source-list header enters the first stream; Up from the first row returns to the header. The current fix prevents an Android 8 long-list jump that could land directly on row 60 of 70, a 720p choice in the reproduced case. The Sony post-fix check passed all 70 rows in two complete round trips, middle-source/file choices, exact Back restoration, and fully visible focus. Those choices were offline fixtures; authenticated transfers remain a separate validation step.

## Continue Watching

Highlight a Continue Watching title and hold **Select/OK**, or press **Menu** if the TV sends that key to apps. Choose **Resume** or **Remove from Continue Watching**. Removal hides that title in POV on this device and does not delete Trakt, MDBList, or Simkl watch history. Intentionally playing the title again restores its eligibility. An older raw TorBox cloud-history entry may require reselecting the file from the current cloud library, since the old entry has no account binding.

## Local and downloaded subtitles

Open **Settings → Subtitles** to choose a preferred language and configure services. In the player's **Subtitles** sheet, choose a file or search online.

- **Load an SRT file:** Android's document picker provides access to device files and supported USB drives. Network folders appear when an installed document provider exposes them. A TV without a document picker needs a compatible document-provider app; POV does not include its own SMB browser. Files are copied into private app cache for playback. No broad storage permission is requested.
- **OpenSubtitles:** enter your own API key and account username/password. Obtain these through [OpenSubtitles](https://www.opensubtitles.com/). Search and downloads use its official REST API and account quotas.
- **SubDL:** enter your own API key from [SubDL](https://subdl.com/).
- **SubSource:** enter your own API key from [SubSource](https://subsource.net/).

Search results show their provider and release name. Select a result matching the video. A preferred language does not guarantee that every provider has a matching subtitle. Downloads are explicit, may consume service quota, and are held in private cache rather than exported to shared storage.

## Playback services

Open **Settings → Playback service accounts** to connect a service. Then choose it under **Playback service**, or use the service selector on the source/library screen. Linking another service does not disconnect existing accounts.

| Service | Setup |
| --- | --- |
| TorBox | Existing device activation or your own API key from [TorBox settings](https://torbox.app/settings). |
| Real-Debrid | Your own [private API token](https://real-debrid.com/apitoken). |
| AllDebrid | Your own [API key](https://alldebrid.com/apikeys/). |
| Premiumize | Your own API key from [your account](https://www.premiumize.me/account). |
| Offcloud | Your own API key from [your account](https://offcloud.com/#/account). |
| Easynews | Your own [member](https://members.easynews.com/) username/password. Experimental integration with the member search service; protected streams use Media3. |

Service subscriptions, quotas, cache status, host support, and processing times belong to those services. POV does not supply accounts or subscription access. Multiple available video versions remain individually selectable. Cache information is not invented for providers that do not expose it through the supported API.

The new provider adapters have automated tests with fake service responses. Authenticated playback through every added provider, MDBList/Simkl synchronization, and online subtitle downloads have not been verified with real accounts in this preview.

## MDBList and Simkl

**MDBList:** open its connection in Settings and enter your own API key from [MDBList preferences](https://mdblist.com/preferences/). Its library offers movie/show watchlists and your own, liked, and external lists. Title details offer explicit MDBList watchlist and watched actions.

**Simkl:** register a public **AUTH V2 TV/device** application in [Simkl developer settings](https://simkl.com/settings/developer/), enter its Client ID in POV, then choose **Connect Simkl**. Approve the displayed device code on your own phone/browser. A Simkl AUTH V1 Client ID will not work with this V2 connection. No client secret is needed. The library offers watchlists, saved playback, and explicit custom-list browsing. Simkl custom lists require PRO/VIP.

Trakt keeps its existing connection and playback tracking. Studio can read the connected Trakt account's watched shows and episode progress for its Up Next column; fetching that column does not add watched history. The new service connections are independent. **MDBList and Simkl playback tracking default to Off.** Enabling either displays a consent dialog describing the title/episode IDs, playback percentage, and play/pause/stop events sent to that service. The choice is bound to the current account. Disabling, disconnecting, or changing account prevents old queued updates from being sent to another account. Enabling starts fresh and does not import old history.

Your service credentials remain encrypted on the device. The build expects you to enter your own credentials after installation; account data, activation codes, and personal viewing history are excluded from public packaging. The unmodified VLC dependency contains its own upstream public playlist client identifiers, as described in [Privacy](PRIVACY.md). Backup exclusions remain in place. See also [LibVLC licensing/source notes](LIBVLC.md).

## Building or reviewing source on Windows

Use **PowerShell 7 (`pwsh`)** for the source-policy, packaging, and GitHub-preparation scripts. They use .NET APIs that are unavailable in the older Windows PowerShell 5.1 host. Run `scripts/bootstrap.ps1` and `scripts/build.ps1` from the matching source checkout to provision and use the project-local build tools. Source packaging and artifact checks remain separate local steps; they do not publish a release.

The embedded VLC dependency makes the universal APK substantially larger than earlier previews. Its upstream license notices and native corresponding-source/rebuild requirements are described in [LibVLC notes](LIBVLC.md). The app-source ZIP does not yet include a complete, checked native LibVLC/VLC/contrib corresponding-source bundle or a verified replacement-library rebuild. Completing that work remains necessary before broad publication of this VLC-enabled build; a successful app build or privacy scan does not complete it.
