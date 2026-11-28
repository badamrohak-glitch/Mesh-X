package com.meshx.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores periodic offline storage maintenance after device reboot.
 * Respects Android 12+ foreground service restrictions by recording a pending-resume flag
 * rather than attempting an illegal background FGS start from BOOT_COMPLETED.
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val appCtx = context.applicationContext
        MeshMaintenanceJobService.schedule(appCtx)

        if (MeshForegroundService.isBackgroundEnabled(appCtx)) {
            MeshForegroundService.setPendingResume(appCtx, true)
        }
    }
}
