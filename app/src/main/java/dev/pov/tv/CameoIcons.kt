package dev.pov.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Launcher appearance is independent from in-app themes and account/preferences storage. */
class CameoIcons(context: Context) {
    data class Choice(val id: String, val label: String, val iconRes: Int, internal val alias: String)
    private val context = context.applicationContext
    private val manager = this.context.packageManager
    private val preferences = this.context.getSharedPreferences("cameo_launcher_icon", Context.MODE_PRIVATE)
    val choices: List<Choice> get() = CHOICES

    fun selected(): String = choices.firstOrNull { enabled(it) }?.id
        ?: preferences.getString("selected", DEFAULT).takeIf { id -> choices.any { it.id == id } }
        ?: DEFAULT

    /** Enables exactly one alias. MainActivity itself retains its original explicit component name. */
    fun select(id: String): Boolean {
        val target = choices.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown Cameo icon.")
        val before = choices.associateWith { manager.getComponentEnabledSetting(component(it)) }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.setComponentEnabledSettings(choices.map { choice ->
                    PackageManager.ComponentEnabledSetting(component(choice),
                        if (choice == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)
                })
            } else {
                // Keep a launchable component throughout the older non-atomic API sequence.
                manager.setComponentEnabledSetting(component(target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                choices.filter { it != target }.forEach { choice ->
                    manager.setComponentEnabledSetting(component(choice), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
            preferences.edit().putString("selected", target.id).apply()
            true
        } catch (_: RuntimeException) {
            before.forEach { (choice, state) ->
                runCatching { manager.setComponentEnabledSetting(component(choice), state, PackageManager.DONT_KILL_APP) }
            }
            false
        }
    }

    private fun component(choice: Choice) = ComponentName(context.packageName, "dev.pov.tv.${choice.alias}")
    private fun enabled(choice: Choice): Boolean = when (manager.getComponentEnabledSetting(component(choice))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> choice.id == DEFAULT
        else -> false
    }

    companion object {
        private const val DEFAULT = "blue"
        private val CHOICES = listOf(
            Choice("blue", "Blue", R.drawable.cameo_icon, "CameoBlue"),
            Choice("violet", "Violet", R.drawable.cameo_icon_violet, "CameoViolet"),
            Choice("pearl", "Pearl", R.drawable.cameo_icon_pearl, "CameoPearl"),
            Choice("gold", "Gold", R.drawable.cameo_icon_gold, "CameoGold"),
            Choice("mint", "Mint", R.drawable.cameo_icon_mint, "CameoMint"),
        )
    }
}
