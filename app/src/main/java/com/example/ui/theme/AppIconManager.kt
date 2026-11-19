package com.example.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.example.data.ThemePalette

object AppIconManager {

    fun isPaletteIconActive(context: Context, palette: ThemePalette): Boolean {
        val pm = context.packageManager
        val comp = ComponentName(context.packageName, palette.aliasClassName)
        return isEffectivelyEnabled(pm, comp, defaultEnabled = palette == ThemePalette.PURPLE)
    }

    fun applyPaletteIcon(context: Context, palette: ThemePalette) {
        val pm = context.packageManager
        val pkg = context.packageName
        runCatching {
            val targetComp = ComponentName(pkg, palette.aliasClassName)
            if (!isEffectivelyEnabled(pm, targetComp, defaultEnabled = palette == ThemePalette.PURPLE)) {
                pm.setComponentEnabledSetting(
                    targetComp,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            ThemePalette.entries.forEach { other ->
                if (other != palette) {
                    val otherComp = ComponentName(pkg, other.aliasClassName)
                    if (isEffectivelyEnabled(pm, otherComp, defaultEnabled = other == ThemePalette.PURPLE)) {
                        pm.setComponentEnabledSetting(
                            otherComp,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
            }
        }
    }

    private fun isEffectivelyEnabled(
        pm: PackageManager,
        component: ComponentName,
        defaultEnabled: Boolean
    ): Boolean {
        return when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> defaultEnabled
        }
    }
}
