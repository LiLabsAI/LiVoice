package com.aliahad.wovoice.background

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

/**
 * Xiaomi HyperOS / MIUI & Android background-survival detection and direct settings launchers.
 *
 * On Xiaomi HyperOS (e.g., Redmi K80 Pro / HyperOS 4), swiping an app away in Recents
 * (`SwipeUpClean`) executes a full `FORCE STOP` (revoking Accessibility grants) unless
 * HyperOS Autostart (app-op 10008) and Battery saver -> "No restrictions" are enabled.
 */
object HyperOS {
    const val OP_BOOT_COMPLETED = 10007
    const val OP_AUTO_START = 10008
    const val OP_BACKGROUND_START_ACTIVITY = 10021
    const val OP_SERVICE_FOREGROUND = 10023

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")

    /**
     * Reads a MIUI/HyperOS per-app op via AppOpsManager.
     * Returns true/false when exposed by the ROM, or null when unreadable.
     */
    fun opAllowed(context: Context, op: Int): Boolean? = try {
        val manager = context.getSystemService(AppOpsManager::class.java)
        val method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val mode = method.invoke(manager, op, context.applicationInfo.uid, context.packageName) as Int
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        null
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun notificationsEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }.getOrDefault(true)

    private fun packageIntent(action: String, context: Context): Intent = Intent(action)
        .putExtra("package_name", context.packageName)
        .putExtra("extra_pkgname", context.packageName)
        .putExtra("packageName", context.packageName)

    fun open(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            val launched = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) return true
        }
        return false
    }

    fun appDetails(context: Context): Boolean = open(
        context,
        Intent()
            .setComponent(ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "LiVoice"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    fun autostart(context: Context): Boolean =
        open(context, packageIntent("miui.intent.action.OP_AUTO_START", context)) || appDetails(context)

    fun otherPermissions(context: Context): Boolean = open(
        context,
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            .putExtra("extra_pkgname", context.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName),
    ) || appDetails(context)

    @SuppressLint("BatteryLife")
    fun battery(context: Context): Boolean = open(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    ) || appDetails(context)

    fun notifications(context: Context): Boolean = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    ) || appDetails(context)

    fun accessibility(context: Context): Boolean = open(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )
}
