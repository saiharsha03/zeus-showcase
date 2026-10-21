package dev.phonerecall.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dev.phonerecall.odyssey.Act
import dev.phonerecall.odyssey.Odyssey

/**
 * The one drive detector, shared by music and Odyssey: Play services' activity recognition says
 * IN_VEHICLE started or ended, plus STILL and WALKING for Odyssey's stays (on the phone, no location
 * trail). Armed while either "Detect driving" (Themis, Music) or Odyssey's tracking is on. A drive start
 * makes a music_while_driving proposal (it runs by itself only if that kind is on his auto-approve list)
 * and, for Odyssey, closes the stay and starts the drive report.
 */
object DriveSense {
    const val ACTION = "dev.phonerecall.DRIVE_TRANSITION"

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun wanted(c: Context) = MusicCore.get(c).state.value.driveSense || Odyssey.tracking(c)

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, 61_100, Intent(c, DriveReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun arm(c: Context) {
        if (!allowed(c) || !wanted(c)) return
        val vehicle = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        val stays = listOf(DetectedActivity.STILL, DetectedActivity.WALKING).map {
            ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        }
        runCatching { ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(vehicle + stays), pending(c)) }
    }

    /** Music's switch went off: stop listening unless Odyssey still needs the transitions. */
    @SuppressLint("MissingPermission")
    fun disarm(c: Context) {
        if (!allowed(c)) return
        if (wanted(c)) arm(c) else runCatching { ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pending(c)) }
        if (!MusicCore.get(c).state.value.driveSense) MusicCore.get(c).driveEnded()
    }
}

class DriveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action != DriveSense.ACTION) return
        val r = ActivityTransitionResult.extractResult(intent) ?: return
        val core = MusicCore.get(app)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val acts = r.transitionEvents.mapNotNull { e ->
            val at = nowWall - (nowElapsed - e.elapsedRealTimeNanos) / 1_000_000L
            when {
                e.activityType == DetectedActivity.IN_VEHICLE && e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> Act.VEHICLE_ENTER to at
                e.activityType == DetectedActivity.IN_VEHICLE -> Act.VEHICLE_EXIT to at
                e.activityType == DetectedActivity.STILL -> Act.STILL_ENTER to at
                e.activityType == DetectedActivity.WALKING -> Act.WALKING_ENTER to at
                else -> null
            }
        }
        val last = r.transitionEvents.lastOrNull { it.activityType == DetectedActivity.IN_VEHICLE }
        // Every vehicle transition in Clio: the first thing to read when a drive goes missing.
        if (last != null) dev.phonerecall.clio.Clio.info("odyssey", "vehicle " + if (last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "enter" else "exit")
        val done = goAsync()
        Thread {
            try {
                if (last != null && core.state.value.driveSense) {
                    if (last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) core.driveStarted() else core.driveEnded()
                }
                if (acts.isNotEmpty()) Odyssey.transitions(app, acts)
            } finally { done.finish() }
        }.start()
    }
}
