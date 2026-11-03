package dev.pov.tv

/** Mobile composition is independent of the television grid and uses native density. */
internal data class MobileThemeProfile(
    val hero: MobileHeroComposition,
    val tabs: MobileTabStyle,
    val insetDp: Int,
    val cardRadiusDp: Int,
    val buttonRadiusDp: Int,
    val floatingNavigation: Boolean,
    val titleSizeSp: Int,
    val posterHeightRatio: Float,
) {
    val fullBleedHero: Boolean get() = hero in setOf(MobileHeroComposition.GLASS, MobileHeroComposition.CINEMA, MobileHeroComposition.AURORA)
    val headerOverArtwork: Boolean get() = hero == MobileHeroComposition.GLASS

    companion object {
        fun forTheme(theme: ThemeSpec): MobileThemeProfile = when (theme) {
            ThemeSpec.GLASS -> MobileThemeProfile(MobileHeroComposition.GLASS, MobileTabStyle.GLASS_PILLS, 16, 10, 12, true, 32, 1.25f)
            ThemeSpec.PREMIERE -> MobileThemeProfile(MobileHeroComposition.CINEMA, MobileTabStyle.UNDERLINE, 12, 8, 8, false, 42, 1.14f)
            ThemeSpec.ESSENTIAL -> MobileThemeProfile(MobileHeroComposition.SLATE, MobileTabStyle.SOLID_PILLS, 12, 11, 13, false, 27, 1.14f)
            ThemeSpec.ATELIER -> MobileThemeProfile(MobileHeroComposition.PEARL, MobileTabStyle.UNDERLINE, 12, 10, 9, false, 31, 1.0f)
            ThemeSpec.HORIZON -> MobileThemeProfile(MobileHeroComposition.AURORA, MobileTabStyle.CAPSULE, 16, 11, 13, true, 28, 1.10f)
            else -> MobileThemeProfile(MobileHeroComposition.SLATE, MobileTabStyle.SOLID_PILLS, 16, 12, 12, false, 28, 1.14f)
        }
    }
}

internal enum class MobileHeroComposition { GLASS, CINEMA, SLATE, PEARL, AURORA }
internal enum class MobileTabStyle { GLASS_PILLS, SOLID_PILLS, UNDERLINE, CAPSULE }
