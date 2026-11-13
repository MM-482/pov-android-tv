# Install Cameo 0.4.0 for Android

The launcher name is **Cameo**. Version **0.4.0-preview** continues the existing `dev.pov.tv` application so a signature-compatible update can retain existing POV connections, appearance and viewing progress. Do not uninstall or clear app storage to perform a normal update.

## Phone, foldable or tablet

1. Obtain the APK from the release distributing this exact version. Verify its SHA-256 against that release's checksum file.
2. Open it in your Android file manager. Permit installation from that file manager if Android asks, then choose Update or Install.
3. Launch Cameo. Mobile navigation appears automatically; **Settings → Appearance** offers Glass, Cinema, Slate, Pearl and Aurora. Fold/cover size changes rebuild the interface while retaining the in-process navigation and playback session.
4. Open **Library** for local files, folders, playlists, downloads and service libraries. Android's file picker controls access to local, USB and installed network document providers. A provider that offers only temporary access may require selecting a file again in a later session.

Z Fold 8 is the requested mobile target. Geometry tests at cover/unfolded logical viewports do not substitute for testing the physical phone. Android 8.0 is the minimum; hardware decoders, document providers, PiP and receiver support vary.

## Android TV

Transfer the APK to the TV with a trusted file-transfer/file-manager app, open it and approve the update. USB and the device's permitted network-debugging installation are alternatives. Use the D-pad and Select to browse; Back from fullscreen returns to Cameo with the same in-app mini-player session. Menu opens mini-player actions. Stop is integrated beside the main transport controls.

Appearance offers only the five new Cameo TV themes: Glass, Cinema, Slate, Pearl and Aurora. Previous POV selections migrate to a Cameo theme; a fresh TV installation starts with Cameo Glass. Launcher icon selection is independent of the theme. Accounts and viewing progress are retained during a compatible update.

## Connect your services

Open **Settings → Connections**. Each person supplies their own accounts; nothing is preconnected in the public APK.

- TorBox supports activation-code linking or your own API key. Playback services also include Real-Debrid, AllDebrid, Premiumize, Offcloud, Debrid-Link, put.io and Easynews. Saved account profiles can be added/switched without merging their file identities.
- Trakt and Simkl require your own registered application Client ID before device activation. Enter the code shown by Cameo on the service's activation site, approve it, then return to the app. MDBList supports your own API key. Optional additional playback reporting is a separate choice.
- AniList, MyAnimeList and Kitsu use the credentials/token fields shown by their connection form. Anime IDs remain service specific; progress/status/score edits are explicit.
- Plex/Jellyfin/Emby need a reachable server address and the required account token or login. This preview resolves original files, not a promised server-transcoding session. WebDAV needs a server root and credentials. Seedr requires a plan with official WebDAV access.
- Online subtitle services have separate settings for OpenSubtitles, SubDL and SubSource. Download quotas and available languages are controlled by those services.

Use HTTPS for remote servers. HTTP is accepted only for validated local-network endpoints. Credentials stay bound to their server origin; unsupported redirect/auth requirements produce an error rather than silently discarding headers.

## Playback, subtitles and downloads

Choose **Settings → Playback engine** before opening the next video. Media3 remains available alongside embedded VLC; the separate VLC application is not required. Audio and subtitle menus reflect the tracks advertised by the selected file. Local subtitle import supports validated text subtitle formats; some formats require VLC.

Use the player tools for speed, sleep, chapters where present, subtitle delay/appearance and playback details. PiP is an explicit Android option where supported. VLC Cast discovery is explicit, bounded and limited to compatible unprotected sessions; local tests do not confirm receiver pictures.

Downloads ask for an exact source/file and a destination. Choose a new/empty document and keep the app open. Cancel stops a transfer; partial documents may remain where selected. After an app restart an unfinished transfer is marked Interrupted; choose Download again to resolve a fresh source and start over. Streaming manifests cannot be saved as a complete offline movie. Easynews NZB support saves the selected assembled file; RAR/PAR2 repair or extraction is not included.

## Troubleshooting and privacy

Check service subscription validity and the exact source/file before assuming a player failure. A frozen-picture recovery message can offer an engine retry. An HTTP access refusal must be resolved with the service; changing advertised quality does not bypass it.

**Settings → Troubleshooting report** saves only allowlisted runtime information and connection booleans. Review any report before sharing it. Never share account tokens, activation codes, configured add-on addresses, private playback URLs, or screenshots of personal libraries. See [Privacy](PRIVACY.md) and the [version-specific validation record](AUDIT_0.4.0.md).
