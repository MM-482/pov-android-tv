package dev.pov.tv

/** Native TV geometry derived from the November 2 Cameo studies, on a 960 x 540 canvas. */
data class ConceptThemeProfile(
    val previewAsset: String,
    val railX: Int = 0, val railY: Int = 0, val railWidth: Int = 150, val railHeight: Int = 540,
    val logoX: Int = 23, val logoY: Int = 27, val logoWidth: Int = 112, val logoHeight: Int = 30,
    val navX: Int = 12, val navY: Int = 83, val navWidth: Int = 130, val navHeight: Int = 38, val navStep: Int = 44,
    val navIconInset: Int = 16,
    val contentLeft: Int = 173, val contentRight: Int = 13, val headerHeight: Int = 0,
    val heroCopyX: Int = 193, val heroCopyY: Int = 50, val heroCopyWidth: Int = 285,
    val heroHeight: Int = 223, val titleSize: Int = 38, val titleTracking: Float = -.025f,
    val titleHeight: Int = 86, val metadataY: Int = 139, val actionsY: Int = 166,
    val primaryWidth: Int = 117, val secondaryWidth: Int = 106, val actionGap: Int = 12,
    val heroFrameX: Int = 168, val heroFrameY: Int = 36, val heroFrameWidth: Int = 778, val heroFrameHeight: Int = 187,
    val sectionX: Int = 173, val sectionY: Int = 235, val cardsY: Int = 258, val cardsX: Int = sectionX,
    val cardWidth: Int = 252, val cardHeight: Int = 95, val cardGap: Int = 9,
    val cardImageHeight: Int = cardHeight, val labelsOutside: Boolean = false,
    val discoverSectionY: Int = 366, val discoverCardsY: Int = 389,
    val discoverCardWidth: Int = 148, val discoverCardHeight: Int = 122, val discoverCardGap: Int = 8,
    val radius: Int = 8, val buttonRadius: Int = 8, val footerY: Int = 519,
) {
    val watchingWidth: Int get() = cardWidth * 3 + cardGap * 2
    val discoverWidth: Int get() = discoverCardWidth * 5 + discoverCardGap * 4

    companion object {
        fun forTheme(theme: ThemeSpec): ConceptThemeProfile? = when (theme) {
            ThemeSpec.GLASS -> ConceptThemeProfile("theme_previews/01-glass-apple-tv.png",
                railX = 11, railY = 14, railWidth = 142, railHeight = 512,
                logoX = 29, logoY = 35, logoWidth = 112, logoHeight = 30,
                navX = 18, navY = 87, navWidth = 128, navHeight = 37, navStep = 43, navIconInset = 16,
                contentLeft = 174, contentRight = 14, heroCopyX = 184, heroCopyY = 62, heroCopyWidth = 280,
                heroHeight = 239, titleSize = 39, titleTracking = -.02f, titleHeight = 84,
                metadataY = 146, actionsY = 178, primaryWidth = 136, secondaryWidth = 126, actionGap = 12,
                heroFrameX = 0, heroFrameY = 0, heroFrameWidth = 960, heroFrameHeight = 540,
                sectionX = 174, sectionY = 244, cardsY = 270, cardWidth = 247, cardHeight = 89, cardGap = 14,
                discoverSectionY = 377, discoverCardsY = 400, discoverCardWidth = 148,
                discoverCardHeight = 119, discoverCardGap = 8, radius = 8, buttonRadius = 11, footerY = 498)
            ThemeSpec.ESSENTIAL -> ConceptThemeProfile("theme_previews/02-essential-classic.png")
            ThemeSpec.PREMIERE -> ConceptThemeProfile("theme_previews/03-premiere-cinematic.png",
                railWidth = 0, railHeight = 0, logoX = 28, logoY = 12, logoWidth = 135, logoHeight = 24,
                navX = 194, navY = 8, navWidth = 64, navHeight = 31, navStep = 76,
                contentLeft = 34, contentRight = 34, headerHeight = 51,
                heroCopyX = 46, heroCopyY = 60, heroCopyWidth = 350, heroHeight = 246,
                titleSize = 46, titleTracking = .10f, titleHeight = 96, metadataY = 164, actionsY = 190,
                primaryWidth = 119, secondaryWidth = 105, actionGap = 12,
                heroFrameX = 0, heroFrameY = 0, heroFrameWidth = 960, heroFrameHeight = 540,
                sectionX = 34, sectionY = 254, cardsY = 278, cardWidth = 291, cardHeight = 81, cardGap = 10,
                discoverSectionY = 373, discoverCardsY = 397, discoverCardWidth = 174,
                discoverCardHeight = 111, discoverCardGap = 8, radius = 3, buttonRadius = 6, footerY = 519)
            ThemeSpec.ATELIER -> ConceptThemeProfile("theme_previews/04-atelier-light.png",
                railWidth = 0, railHeight = 0, logoX = 45, logoY = 5, logoWidth = 137, logoHeight = 31,
                navX = 248, navY = 4, navWidth = 66, navHeight = 31, navStep = 77,
                contentLeft = 25, contentRight = 25, headerHeight = 50,
                heroCopyX = 44, heroCopyY = 59, heroCopyWidth = 253, heroHeight = 235,
                titleSize = 43, titleTracking = -.025f, titleHeight = 92, metadataY = 147, actionsY = 183,
                primaryWidth = 128, secondaryWidth = 108, actionGap = 12,
                heroFrameX = 316, heroFrameY = 52, heroFrameWidth = 622, heroFrameHeight = 181,
                sectionX = 28, sectionY = 244, cardsX = 25, cardsY = 269, cardWidth = 296, cardHeight = 93, cardGap = 11,
                discoverSectionY = 376, discoverCardsY = 401, discoverCardWidth = 174,
                discoverCardHeight = 107, discoverCardGap = 10, radius = 10, buttonRadius = 10, footerY = 519)
            ThemeSpec.HORIZON -> ConceptThemeProfile("theme_previews/05-horizon-futuristic.png",
                railWidth = 0, railHeight = 0, logoX = 34, logoY = 11, logoWidth = 126, logoHeight = 28,
                navX = 268, navY = 12, navWidth = 64, navHeight = 29, navStep = 64,
                contentLeft = 26, contentRight = 25, headerHeight = 54,
                heroCopyX = 61, heroCopyY = 74, heroCopyWidth = 285, heroHeight = 238,
                titleSize = 35, titleTracking = -.02f, titleHeight = 77, metadataY = 151, actionsY = 182,
                primaryWidth = 115, secondaryWidth = 96, actionGap = 14,
                heroFrameX = 25, heroFrameY = 54, heroFrameWidth = 912, heroFrameHeight = 183,
                sectionX = 26, sectionY = 249, cardsY = 276, cardWidth = 297, cardHeight = 86, cardGap = 8,
                discoverSectionY = 373, discoverCardsY = 397, discoverCardWidth = 176,
                discoverCardHeight = 111, discoverCardGap = 8, radius = 11, buttonRadius = 10, footerY = 519)
            ThemeSpec.STUDIO -> ConceptThemeProfile("theme_previews/06-studio-dashboard.png",
                railWidth = 166, logoX = 33, logoY = 42, logoWidth = 68, logoHeight = 33,
                navX = 19, navY = 100, navWidth = 137, navHeight = 41, navStep = 46, navIconInset = 16,
                contentLeft = 178, contentRight = 21, sectionX = 178, sectionY = 33, cardsY = 63,
                cardWidth = 496, cardHeight = 276, cardGap = 10, radius = 10, buttonRadius = 9, footerY = 518)
            else -> null
        }
    }
}

/** The four defining colors for each selectable appearance come from the supplied assets.json. */
internal fun conceptPalette(id: String): ThemePalette = when (id) {
    "glass" -> ThemePalette(
        background = 0xFF0B1728.toInt(), panel = 0xFF314E6A.toInt(), text = 0xFFF5F8FC.toInt(),
        muted = 0xFFB9CADA.toInt(), accent = 0xFF87C8FF.toInt(), secondaryAccent = 0xFF87C8FF.toInt(),
        buttonText = 0xFF0B1728.toInt(), border = 0xFF5A7790.toInt(), navSelected = 0xFF314E6A.toInt(),
        progress = 0xFF87C8FF.toInt(), primaryButton = 0xFFF5F8FC.toInt())
    "premiere" -> ThemePalette(
        background = 0xFF080807.toInt(), panel = 0xFF1B1813.toInt(), text = 0xFFF9F3E6.toInt(),
        muted = 0xFFC7C0B1.toInt(), accent = 0xFFD3AC70.toInt(), secondaryAccent = 0xFFE6BE84.toInt(),
        buttonText = 0xFF080807.toInt(), border = 0xFF6F604A.toInt(), navSelected = 0xFF251F16.toInt(),
        progress = 0xFFE6BE84.toInt(), primaryButton = 0xFFD3AC70.toInt())
    "essential" -> ThemePalette(
        background = 0xFF1B2025.toInt(), panel = 0xFF2A3036.toInt(), text = 0xFFF4F6F7.toInt(),
        muted = 0xFFB0B7BD.toInt(), accent = 0xFF00C986.toInt(), secondaryAccent = 0xFF00C986.toInt(),
        buttonText = 0xFF071C14.toInt(), border = 0xFF42494F.toInt(), navSelected = 0xFF243D34.toInt(),
        progress = 0xFF00C986.toInt(), primaryButton = 0xFF00C986.toInt())
    "atelier" -> ThemePalette(
        background = 0xFFF8F4EF.toInt(), panel = 0xFFECE8E3.toInt(), text = 0xFF24292D.toInt(),
        muted = 0xFF62696E.toInt(), accent = 0xFF315779.toInt(), secondaryAccent = 0xFF81A1BF.toInt(),
        buttonText = 0xFFF8F4EF.toInt(), border = 0xFFC5CBCD.toInt(), navSelected = 0xFFDDE6ED.toInt(),
        progress = 0xFF81A1BF.toInt(), primaryButton = 0xFF315779.toInt())
    "horizon" -> ThemePalette(
        background = 0xFF0F1024.toInt(), panel = 0xFF24143C.toInt(), text = 0xFFEDEFFB.toInt(),
        muted = 0xFFBEC2D6.toInt(), accent = 0xFF13DBE0.toInt(), secondaryAccent = 0xFF8870D8.toInt(),
        buttonText = 0xFF0F1024.toInt(), border = 0xFF4B4168.toInt(), navSelected = 0xFF25394A.toInt(),
        progress = 0xFF13DBE0.toInt(), primaryButton = 0xFF13DBE0.toInt())
    else -> ThemePalette(
        background = 0xFF0B0F15.toInt(), panel = 0xFF1C2028.toInt(), text = 0xFFF4F6FC.toInt(),
        muted = 0xFFB7C3D8.toInt(), accent = 0xFF02E2FB.toInt(), secondaryAccent = 0xFFBA44FF.toInt(),
        buttonText = 0xFF03070A.toInt(), border = 0xFF2B394A.toInt(), navSelected = 0xFF102437.toInt(),
        progress = 0xFF02CDFA.toInt(), primaryButton = 0xFF01C4FE.toInt())
}

internal fun conceptLayout(id: String): ThemeLayout {
    val top = id in setOf("premiere", "atelier", "horizon")
    return ThemeLayout(if (top) NavigationPlacement.TOP_BAR else NavigationPlacement.LEFT_RAIL,
        when (id) { "atelier" -> HeroComposition.SPLIT; "studio" -> HeroComposition.RESUME_GRID
            "essential", "horizon" -> HeroComposition.FRAMED; else -> HeroComposition.FULL_BLEED },
        CardFormat.LANDSCAPE, contentInsetDp = if (top) 32 else 16,
        railWidthDp = if (top) 0 else if (id == "studio") 160 else 150,
        headerHeightDp = if (top) 54 else 0,
        heroHeightDp = if (id == "studio") 276 else 315, cardWidthDp = if (top) 216 else 190, cardHeightDp = 132,
        cardGapDp = 10, cornerRadiusDp = if (id == "horizon" || id == "glass") 11 else 8,
        titleSizeSp = if (id == "premiere") 46 else 40,
        titleTypeface = when (id) { "premiere" -> "sans-serif-condensed"; "atelier" -> "serif"; else -> "sans-serif" },
        titleLetterSpacing = if (id == "premiere") .1f else -.02f,
        sectionTitleSizeSp = 18, focusScale = 1.012f)
}
