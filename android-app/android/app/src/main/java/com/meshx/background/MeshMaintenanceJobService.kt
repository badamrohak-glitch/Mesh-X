package com.meshx.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.meshx.storage.MeshLocalStore

/**
 * Battery-friendly periodic maintenance job using Android's built-in JobScheduler.
 * Cleans up expired store-and-forward relay packets offline without waking the radio.
 */
class MeshMaintenanceJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        MeshLocalStore.getInstance(applicationContext).pruneExpiredRelayPackets()
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 4201
        private const val INTERVAL_MS = 30L * 60L * 1000L

        fun schedule(context: Context) {
            val appCtx = context.applicationContext
            val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val existing = scheduler.allPendingJobs.any { it.id == JOB_ID }
            if (existing) {
                return
            }

            val component = ComponentName(appCtx, MeshMaintenanceJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }
    }
}
