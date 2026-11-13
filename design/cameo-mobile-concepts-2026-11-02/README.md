# Cameo Android mobile — five theme concepts

Created November 2, 2026 for the proposed Cameo Android mobile app. These are five portrait phone interface concepts with matching Glass, Cinema, Slate, Pearl, and Aurora design families.

**These are visual concepts, not implemented app screens or a new APK.** Artwork, titles, and viewing progress are illustrative. The concepts do not connect to accounts or demonstrate live playback.

The approved reference is the **one-to-one visual target for later mobile implementation**. Preserve its composition, spacing, typography, colors, imagery treatment, and control placement. Mobile and Android TV have separate layout references while sharing the same theme identity; a landscape TV screen is not the mobile implementation target. Record the reference dimensions from `assets.json` together with the implementation viewport and display density, then compare app captures at that defined screen size before claiming a visual match. The current app has not been changed to match these references.

Open `index.html` directly in a modern browser. It uses local images, system fonts, inline styles, and inline JavaScript; it does not need a server or internet connection. The gallery shows three columns on wide screens, two on medium screens, and one on narrow screens. Each complete portrait image stays visible without cropping.

Select an image to enlarge it. Use the Previous and Next buttons or the Left and Right arrow keys to compare concepts. Press Escape or select Close to return to the gallery. Keyboard focus returns to the preview that opened the viewer.

| # | Theme | Visual direction | Image |
|---|---|---|---|
| 1 | Glass | Cool blue glass and an immersive featured title | `01-cameo-mobile-glass.png` |
| 2 | Cinema | Matte black, warm champagne and dramatic typography | `02-cameo-mobile-cinema.png` |
| 3 | Slate | Graphite surfaces, emerald accents and clear content hierarchy | `03-cameo-mobile-slate.png` |
| 4 | Pearl | Light ivory, dark editorial type and slate blue details | `04-cameo-mobile-pearl.png` |
| 5 | Aurora | Midnight violet, cyan accents and restrained futuristic glow | `05-cameo-mobile-aurora.png` |

The images show the app screens without phone frames. These mobile studies are separate from the existing landscape Android TV concepts, which remain available in [the Android TV comparison gallery](../cameo-android-concepts-2026-11-02/index.html).

`assets.json` records the final image filenames, dimensions, and palettes. `prompts.json` records the generation brief. Small differences in illustrative content are part of the generated concepts and do not represent app behavior.

This concept gallery does not change the Android application, APK, package identity, account storage, Android TV interface, GitHub repository, or published announcements. Feature selection and implementation are separate work after concept review.
