package dev.pov.tv

import android.content.Context

/**
 * The five visual directions in design/01-cinematic.png through 05-dashboard.png.
 *
 * Dimensions reproduce the concepts on a 960 x 540 logical canvas. Layout code can
 * scale these values together for other TV sizes while retaining their proportions.
 * Theme changes contain presentation only; account and playback state are separate.
 */
enum class ThemeSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val palette: ThemePalette,
    val layout: ThemeLayout,
) {
    CINEMATIC(
        id = "cinematic",
        displayName = "Cinematic",
        description = "Full-screen artwork, a slim left rail, and cyan focus.",
        palette = ThemePalette(
            background = 0xFF020811.toInt(), panel = 0xFF0C1929.toInt(),
            text = 0xFFF5F8FD.toInt(), muted = 0xFFBAC4D5.toInt(),
            accent = 0xFF00D5ED.toInt(), secondaryAccent = 0xFFAA61EA.toInt(),
            buttonText = 0xFF00101A.toInt(), border = 0xFF1D3041.toInt(),
            navSelected = 0xFF032037.toInt(), progress = 0xFF20C9EC.toInt(),
        ),
        layout = ThemeLayout(
            navigation = NavigationPlacement.LEFT_RAIL, hero = HeroComposition.FULL_BLEED,
            cards = CardFormat.LANDSCAPE, contentInsetDp = 14, railWidthDp = 104,
            headerHeightDp = 0, heroHeightDp = 297, cardWidthDp = 202, cardHeightDp = 110,
            cardGapDp = 10, cornerRadiusDp = 5, titleSizeSp = 43,
            titleTypeface = "sans-serif-thin", titleLetterSpacing = 0.16f,
            sectionTitleSizeSp = 15, focusScale = 1.018f,
        ),
    ),
    EDITORIAL(
        id = "editorial",
        displayName = "Editorial",
        description = "An ivory canvas, a split feature, and a clean poster collection.",
        palette = ThemePalette(
            background = 0xFFFFFCF7.toInt(), panel = 0xFFF0EDE7.toInt(),
            text = 0xFF12203F.toInt(), muted = 0xFF656970.toInt(),
            accent = 0xFF6AD7FF.toInt(), secondaryAccent = 0xFFAD33ED.toInt(),
            buttonText = 0xFFF9FCFF.toInt(), border = 0xFFDAD9D5.toInt(),
            navSelected = 0xFFEEE8F8.toInt(), progress = 0xFF6AD7FF.toInt(),
            primaryButton = 0xFF16264B.toInt(),
        ),
        layout = ThemeLayout(
            navigation = NavigationPlacement.TOP_BAR, hero = HeroComposition.SPLIT,
            cards = CardFormat.POSTER, contentInsetDp = 34, railWidthDp = 0,
            headerHeightDp = 59, heroHeightDp = 225, cardWidthDp = 170, cardHeightDp = 197,
            cardGapDp = 11, cornerRadiusDp = 8, titleSizeSp = 44,
            titleTypeface = "sans-serif-condensed", titleLetterSpacing = 0.12f,
            sectionTitleSizeSp = 17, focusScale = 1.018f,
        ),
    ),
    NEON(
        id = "neon",
        displayName = "Neon",
        description = "True black, a framed hero, and electric cyan and magenta.",
        palette = ThemePalette(
            background = 0xFF000000.toInt(), panel = 0xFF0A0A13.toInt(),
            text = 0xFFFFFFFF.toInt(), muted = 0xFFCBCBD4.toInt(),
            accent = 0xFF00E7F3.toInt(), secondaryAccent = 0xFFE700EE.toInt(),
            buttonText = 0xFFFFFFFF.toInt(), border = 0xFF283039.toInt(),
            navSelected = 0xFF10232B.toInt(), progress = 0xFFE500DB.toInt(),
            primaryButton = 0xFF8600F7.toInt(),
        ),
        layout = ThemeLayout(
            navigation = NavigationPlacement.TOP_BAR, hero = HeroComposition.FRAMED,
            cards = CardFormat.LANDSCAPE, contentInsetDp = 21, railWidthDp = 0,
            headerHeightDp = 65, heroHeightDp = 207, cardWidthDp = 178, cardHeightDp = 98,
            cardGapDp = 10, cornerRadiusDp = 8, titleSizeSp = 58,
            titleTypeface = "sans-serif-condensed", titleLetterSpacing = 0.035f,
            sectionTitleSizeSp = 17, focusScale = 1.025f,
        ),
    ),
    CINEMA(
        id = "cinema",
        displayName = "Cinema",
        description = "Warm charcoal, elegant serif titles, and an amber glow.",
        palette = ThemePalette(
            background = 0xFF100D0B.toInt(), panel = 0xFF221A15.toInt(),
            text = 0xFFFFEAC3.toInt(), muted = 0xFFD8CCBA.toInt(),
            accent = 0xFFFFBB50.toInt(), secondaryAccent = 0xFFE58C32.toInt(),
            buttonText = 0xFF1B1104.toInt(), border = 0xFF3B3027.toInt(),
            navSelected = 0xFF3A2B16.toInt(), progress = 0xFFFFB54D.toInt(),
        ),
        layout = ThemeLayout(
            navigation = NavigationPlacement.TOP_BAR, hero = HeroComposition.FULL_BLEED,
            cards = CardFormat.POSTER, contentInsetDp = 39, railWidthDp = 0,
            headerHeightDp = 66, heroHeightDp = 229, cardWidthDp = 170, cardHeightDp = 194,
            cardGapDp = 11, cornerRadiusDp = 7, titleSizeSp = 48,
            titleTypeface = "serif", titleLetterSpacing = 0.015f,
            sectionTitleSizeSp = 17, focusScale = 1.025f,
        ),
    ),
    DASHBOARD(
        id = "dashboard",
        displayName = "Dashboard",
        description = "Large resume tiles, next episodes, and quick library access.",
        palette = ThemePalette(
            background = 0xFF041019.toInt(), panel = 0xFF0F2330.toInt(),
            text = 0xFFF4F8FD.toInt(), muted = 0xFFA5BCD3.toInt(),
            accent = 0xFF00E6EE.toInt(), secondaryAccent = 0xFF48BDE7.toInt(),
            buttonText = 0xFF001217.toInt(), border = 0xFF203D4D.toInt(),
            navSelected = 0xFF00313B.toInt(), progress = 0xFF00DCE7.toInt(),
        ),
        layout = ThemeLayout(
            navigation = NavigationPlacement.TOP_BAR, hero = HeroComposition.RESUME_GRID,
            cards = CardFormat.LANDSCAPE, contentInsetDp = 35, railWidthDp = 0,
            headerHeightDp = 72, heroHeightDp = 222, cardWidthDp = 281, cardHeightDp = 185,
            cardGapDp = 11, cornerRadiusDp = 11, titleSizeSp = 27,
            titleTypeface = "sans-serif", titleLetterSpacing = 0f,
            sectionTitleSizeSp = 22, focusScale = 1.018f,
        ),
    ),
    GLASS("glass", "Glass", "A floating glass rail, spacious artwork, and soft white focus.", conceptPalette("glass"), conceptLayout("glass")),
    ESSENTIAL("essential", "Essential", "The familiar cyan rail, clear actions, and compact watching cards.", conceptPalette("essential"), conceptLayout("essential")),
    PREMIERE("premiere", "Premiere", "Warm cinema light, a quiet rail, and elegant serif typography.", conceptPalette("premiere"), conceptLayout("premiere")),
    ATELIER("atelier", "Atelier", "An ivory canvas, a split feature, and restrained dark focus.", conceptPalette("atelier"), conceptLayout("atelier")),
    HORIZON("horizon", "Horizon", "A slim top navigation, wide artwork, and cyan-violet focus.", conceptPalette("horizon"), conceptLayout("horizon")),
    STUDIO("studio", "Studio", "A large watching tile, an episode column, and illustrated browse actions.", conceptPalette("studio"), conceptLayout("studio"));

    val conceptProfile: ConceptThemeProfile? get() = ConceptThemeProfile.forTheme(this)
    val isConcept: Boolean get() = conceptProfile != null
    /** Playback controls always sit on a dark scrim, including the light Atelier appearance. */
    val playbackAccent: Int get() = if (this == ATELIER) 0xFFF4F6FC.toInt() else palette.accent
    val previewAsset: String get() = conceptProfile?.previewAsset ?: when (this) {
        CINEMATIC -> "themes/01-cinematic.png"; EDITORIAL -> "themes/02-editorial.png"
        NEON -> "themes/03-neon.png"; CINEMA -> "themes/04-cinema.png"; else -> "themes/05-dashboard.png"
    }

    companion object {
        val selectableThemes: List<ThemeSpec> get() = listOf(GLASS, ESSENTIAL, PREMIERE, ATELIER, HORIZON, STUDIO)
        /** Unknown or old preferences fall back to the user's selected concept. */
        fun fromId(id: String?): ThemeSpec = entries.firstOrNull { it.id == id } ?: CINEMATIC

        fun conceptFromId(id: String?): ThemeSpec = when (val saved = entries.firstOrNull { it.id == id }) {
            CINEMATIC -> ESSENTIAL; EDITORIAL -> ATELIER; NEON -> HORIZON
            CINEMA -> PREMIERE; DASHBOARD -> STUDIO; null -> GLASS; else -> saved
        }
    }
}

data class ThemePalette(
    val background: Int,
    val panel: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val secondaryAccent: Int,
    val buttonText: Int,
    val border: Int,
    val navSelected: Int,
    val progress: Int,
    val primaryButton: Int = accent,
    val success: Int = 0xFF38E965.toInt(),
)

data class ThemeLayout(
    val navigation: NavigationPlacement,
    val hero: HeroComposition,
    val cards: CardFormat,
    val contentInsetDp: Int,
    val railWidthDp: Int,
    val headerHeightDp: Int,
    val heroHeightDp: Int,
    val cardWidthDp: Int,
    val cardHeightDp: Int,
    val cardGapDp: Int,
    val cornerRadiusDp: Int,
    val titleSizeSp: Int,
    val titleTypeface: String,
    val titleLetterSpacing: Float,
    val sectionTitleSizeSp: Int,
    val focusScale: Float,
)

enum class NavigationPlacement { LEFT_RAIL, TOP_BAR }
enum class HeroComposition { FULL_BLEED, SPLIT, FRAMED, RESUME_GRID }
enum class CardFormat { LANDSCAPE, POSTER }

/** Appearance is a non-secret device preference and survives app updates. */
class ThemePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)

    fun selected(): ThemeSpec = ThemeSpec.fromId(preferences.getString(KEY, null))

    /** Native concept upgrade; legacy readers retain their original round-trip behavior. */
    fun selectedConcept(): ThemeSpec {
        val stored = preferences.getString(KEY, null)
        val selected = ThemeSpec.conceptFromId(stored)
        if (stored != selected.id) preferences.edit().putString(KEY, selected.id).apply()
        return selected
    }

    fun select(theme: ThemeSpec) {
        preferences.edit().putString(KEY, theme.id).apply()
    }

    private companion object { const val KEY = "theme" }
}
