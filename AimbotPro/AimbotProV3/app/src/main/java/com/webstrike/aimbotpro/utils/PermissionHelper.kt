package com.webstrike.aimbotpro.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Permission helper — overlay, notifications, accessibility (system-managed).
 * Screen capture is requested via MediaProjection manager (handled by service).
 *
 * minSdk = 26 (Android O) — all `SDK_INT >= M` checks were dead code in v3
 * and have been removed. Only the API 33+ (Tiramisu) POST_NOTIFICATIONS
 * check remains as a real guard.
 */
object PermissionHelper {

    /** True iff the app can draw over other apps. */
    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * Open the system overlay-permission settings page for [activity]'s
     * package. Uses the deprecated [android.app.Activity.startActivityForResult]
     * path because the modern ActivityResultContracts can't return granular
     * permission status for system-settings permissions; callers rely on the
     * next [android.app.Activity.onResume] to re-check via [hasOverlayPermission].
     */
    fun requestOverlayPermission(activity: android.app.Activity, requestCode: Int = 7301) {
        if (!hasOverlayPermission(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            activity.startActivityForResult(intent, requestCode)
        }
    }

    /** True iff POST_NOTIFICATIONS is granted (or not required, on pre-Tiramisu). */
    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    /**
     * Open the system app-info notifications settings page so the user can
     * toggle notifications. We no longer call `activity.requestPermissions`
     * directly — callers should use the modern ActivityResultContracts
     * launcher instead.
     */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Accessibility is granted via system Settings — we cannot request directly.
     * Open the Accessibility settings page so the user can enable our service.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Quick check whether our accessibility service is currently enabled.
     * Uses the class name (not a hardcoded string) so the check stays in
     * sync if the service is ever moved.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val service = "${context.packageName}/${com.webstrike.aimbotpro.service.AimbotAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(service)
    }
}
