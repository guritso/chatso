package com.example.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.example.data.ThemePalette

object AppIconManager {

    const val DEFAULT_ALIAS_CLASS = "com.example.MainActivityAliasDefault"

    private val allAliases: List<String> by lazy {
        listOf(DEFAULT_ALIAS_CLASS) + ThemePalette.entries.map { it.aliasClassName }
    }

    @Volatile
    private var desiredAliasClass: String? = null

    @Volatile
    private var suppressNextFlush: Boolean = false

    fun resolveTargetAlias(palette: ThemePalette, fullThemeEnabled: Boolean): String =
        if (fullThemeEnabled) palette.aliasClassName else DEFAULT_ALIAS_CLASS

    fun scheduleIconSync(palette: ThemePalette, fullThemeEnabled: Boolean) {
        desiredAliasClass = resolveTargetAlias(palette, fullThemeEnabled)
    }

    fun suppressNextFlush() {
        suppressNextFlush = true
    }

    fun clearTemporarySuppression() {
        suppressNextFlush = false
    }

    fun flushPendingIconIfNeeded(context: Context) {
        if (suppressNextFlush) {
            suppressNextFlush = false
            return
        }
        val targetAlias = desiredAliasClass ?: return
        val anyOtherActive = allAliases.any { it != targetAlias && isAliasActive(context, it) }
        if (!isAliasActive(context, targetAlias) || anyOtherActive) {
            applyAlias(context, targetAlias)
        }
    }

    fun isDefaultIconActive(context: Context): Boolean =
        isAliasActive(context, DEFAULT_ALIAS_CLASS)

    fun isPaletteIconActive(context: Context, palette: ThemePalette): Boolean =
        isAliasActive(context, palette.aliasClassName)

    fun isAliasActive(context: Context, aliasClassName: String): Boolean {
        val pm = context.packageManager
        val comp = ComponentName(context.packageName, aliasClassName)
        return isEffectivelyEnabled(pm, comp, defaultEnabled = aliasClassName == DEFAULT_ALIAS_CLASS)
    }

    fun applyAlias(context: Context, targetAliasClassName: String) {
        val pm = context.packageManager
        val pkg = context.packageName
        runCatching {
            val targetComp = ComponentName(pkg, targetAliasClassName)
            if (!isEffectivelyEnabled(
                    pm,
                    targetComp,
                    defaultEnabled = targetAliasClassName == DEFAULT_ALIAS_CLASS
                )
            ) {
                pm.setComponentEnabledSetting(
                    targetComp,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            allAliases.forEach { other ->
                if (other != targetAliasClassName) {
                    val otherComp = ComponentName(pkg, other)
                    if (isEffectivelyEnabled(
                            pm,
                            otherComp,
                            defaultEnabled = other == DEFAULT_ALIAS_CLASS
                        )
                    ) {
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
