package com.webstrike.aimbotpro

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.webstrike.aimbotpro.config.FeatureFlags
import com.webstrike.aimbotpro.databinding.ActivityMainBinding
import com.webstrike.aimbotpro.service.CoreAimbotService
import com.webstrike.aimbotpro.service.MediaProjectionHolder
import com.webstrike.aimbotpro.utils.Logger
import com.webstrike.aimbotpro.utils.PermissionHelper
import com.webstrike.aimbotpro.utils.PerformanceMonitor

/**
 * Single-activity entry point.
 *
 * Responsibilities:
 *  - Display status (running, FPS, permission state)
 *  - Walk user through permissions (overlay, notifications, accessibility)
 *  - Request MediaProjection consent, stash result in [MediaProjectionHolder]
 *  - Start/stop the foreground [CoreAimbotService]
 *
 * The actual mod menu lives inside the overlay (managed by CoreAimbotService), not here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logTag = "MainActivity"

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(logTag, "POST_NOTIFICATIONS granted=$granted")
        refreshUi()
    }

    /**
     * MediaProjection consent launcher. Stashes the resultCode + Intent into
     * [MediaProjectionHolder] so [CoreAimbotService] can pick them up at START.
     */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            MediaProjectionHolder.set(result.resultCode, result.data!!)
            Logger.i(logTag, "MediaProjection consent granted")
            // Now kick off the service for real.
            actuallyStartService()
        } else {
            Logger.w(logTag, "MediaProjection consent denied")
            binding.statusText.text = getString(R.string.perm_capture_required)
        }
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindButtons()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun bindButtons() {
        binding.btnOverlay.setOnClickListener {
            PermissionHelper.requestOverlayPermission(this)
        }
        binding.btnAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }
        binding.btnNotifications.setOnClickListener {
            // Use the modern ActivityResultContracts launcher — the v3 code path
            // used the deprecated activity.requestPermissions which bypassed the
            // launcher and never fired the granted callback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // No permission needed below API 33 — just refresh the UI.
                refreshUi()
            }
        }
        binding.btnStart.setOnClickListener { startAimbot() }
        binding.btnStop.setOnClickListener { stopAimbot() }
    }

    private fun startAimbot() {
        // Verify permissions (best-effort — service will also verify)
        val hasOverlay = PermissionHelper.hasOverlayPermission(this)
        val hasNotif = PermissionHelper.hasNotificationPermission(this)

        if (!hasOverlay) {
            binding.statusText.text = getString(R.string.perm_overlay_required)
            PermissionHelper.requestOverlayPermission(this)
            return
        }
        if (!hasNotif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        // Always request a fresh MediaProjection consent — Android 14+ requires
        // a new grant per session, and consume() in CoreAimbotService clears
        // the holder after each use anyway. (In v3 we cached the Intent; that
        // broke the second start because getMediaProjection returns null with
        // a reused Intent.)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun actuallyStartService() {
        val intent = Intent(this, CoreAimbotService::class.java).apply {
            action = Constants.Actions.START
        }
        startForegroundServiceCompat(intent)
        // NOTE: We no longer set FeatureFlags.serviceRunning = true here.
        // The service sets it itself inside startPipeline() once it has
        // actually booted — avoids the race where the UI shows "Running"
        // but the service silently failed to start (missing consent, etc.).
        // We refresh the UI here for instant feedback; if the service fails,
        // its stopSelf() + our next onResume will correct the UI.
        refreshUi()
    }

    private fun stopAimbot() {
        val intent = Intent(this, CoreAimbotService::class.java).apply {
            action = Constants.Actions.STOP
        }
        startService(intent)
        FeatureFlags.serviceRunning = false
        MediaProjectionHolder.clear()
        refreshUi()
    }

    private fun refreshUi() {
        val running = FeatureFlags.serviceRunning
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.statusText.text = if (running) getString(R.string.status_running) else getString(R.string.status_idle)
        binding.statusText.setTextColor(
            getColor(if (running) R.color.brand_primary else R.color.brand_warning)
        )

        // Permission state hints
        val overlayOk = PermissionHelper.hasOverlayPermission(this)
        val accessOk = PermissionHelper.isAccessibilityEnabled(this)
        val notifOk = PermissionHelper.hasNotificationPermission(this)
        val captureOk = MediaProjectionHolder.hasResult

        binding.btnOverlay.text = if (overlayOk) getString(R.string.status_overlay_on) else getString(R.string.btn_grant_overlay)
        binding.btnAccessibility.text = if (accessOk) getString(R.string.status_accessibility_on) else getString(R.string.btn_grant_accessibility)
        binding.btnNotifications.text = if (notifOk) getString(R.string.status_notifications_on) else getString(R.string.btn_grant_notifications)

        if (running) {
            val fps = PerformanceMonitor.fps().toInt()
            val lat = PerformanceMonitor.averageLatencyMs().toInt()
            binding.fpsText.text = "FPS: $fps | Latency: $lat ms" +
                if (FeatureFlags.demoMode) " | ${Constants.Misc.DEMO_MODE_TEXT}" else ""
        } else {
            binding.fpsText.text = "FPS: -- | Latency: -- ms" +
                if (captureOk) " | " + getString(R.string.status_capture_ready) else ""
        }
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        // minSdk = 26 (Android O) — startForegroundService is always available.
        // The v3 SDK_INT >= O guard was dead code.
        runCatching { startForegroundService(intent) }
            .onFailure {
                Logger.e(logTag, "startForegroundService failed: ${it.message}", it)
                binding.statusText.text = getString(R.string.perm_capture_required)
            }
    }
}
