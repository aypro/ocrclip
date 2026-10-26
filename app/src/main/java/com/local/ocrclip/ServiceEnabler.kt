package com.local.ocrclip

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings

/**
 * The Retroid firmware resets the enabled accessibility services to its own default on boot.
 * With WRITE_SECURE_SETTINGS (granted once via
 * `adb shell pm grant com.local.ocrclip android.permission.WRITE_SECURE_SETTINGS`) the app adds its
 * own service back. Only OCR Clip's entry is ever added; other services are left untouched.
 */
object ServiceEnabler {
    fun canEnable(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean {
        val me = ComponentName(context, OcrService::class.java)
        return enabledList(context).any { ComponentName.unflattenFromString(it) == me }
    }

    /** Adds OCR Clip's service to the enabled list if it's missing. Returns true if it is enabled afterwards. */
    fun ensureEnabled(context: Context): Boolean {
        if (isEnabled(context)) return true
        if (!canEnable(context)) return false
        val me = ComponentName(context, OcrService::class.java).flattenToString()
        val updated = (enabledList(context) + me).joinToString(":")
        val resolver = context.contentResolver
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        return isEnabled(context)
    }

    private fun enabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() } ?: emptyList()
}

/** Re-enables the service after boot or an app update, and re-checks a few times in case the firmware resets it late. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceEnabler.ensureEnabled(context)
        if (intent.action == ACTION_RECHECK) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        RECHECK_DELAYS_MS.forEachIndexed { i, delay ->
            val pending = PendingIntent.getBroadcast(
                context, i, Intent(context, BootReceiver::class.java).setAction(ACTION_RECHECK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, pending)
        }
    }

    companion object {
        const val ACTION_RECHECK = "com.local.ocrclip.RECHECK_SERVICE"
        private val RECHECK_DELAYS_MS = longArrayOf(30_000, 120_000, 300_000)
    }
}
