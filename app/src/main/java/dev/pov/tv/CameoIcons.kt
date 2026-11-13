package dev.pov.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Launcher aliases change presentation without touching the package or existing encrypted accounts. */
internal class CameoIcons(private val context:Context) {
    private val preferences=context.getSharedPreferences("cameo_icons",Context.MODE_PRIVATE)
    fun selected()=preferences.getString("selected","CameoArc")?:"CameoArc"
    fun select(id:String) {
        require(id in OPTIONS.map{it.first}) { "Choose one of the supplied icons." }
        val manager=context.packageManager
        manager.setComponentEnabledSetting(ComponentName(context,"${context.packageName}.$id"),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
        OPTIONS.filterNot{it.first==id}.forEach{(alias,_)->manager.setComponentEnabledSetting(ComponentName(context,"${context.packageName}.$alias"),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)}
        check(preferences.edit().putString("selected",id).commit()) { "The icon choice could not be saved." }
    }
    companion object {val OPTIONS=listOf("CameoArc" to "Cameo Arc","CameoAperture" to "Aperture","CameoEclipse" to "Eclipse","CameoFrame" to "Frame","CameoMonogram" to "Monogram")}
}
