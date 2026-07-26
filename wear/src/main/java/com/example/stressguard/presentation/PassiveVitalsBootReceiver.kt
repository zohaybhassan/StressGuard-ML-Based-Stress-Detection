package com.example.stressguard.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-establishes background vitals collection after a reboot or an app upgrade.
 *
 * Without this the app would monitor in the background only until the watch was restarted, and
 * then silently stop — with nothing on screen to say so, since the watch UI shows the last value
 * it saw. A passive registration is not guaranteed to survive either event, and re-registering is
 * idempotent, so it is done unconditionally rather than checked for.
 *
 * Registration is skipped without the heart rate permission: Health Services would refuse it, and
 * the refusal is not reported to the caller (see docs/architecture-notes.md).
 */
class PassiveVitalsBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val permission = if (Build.VERSION.SDK_INT >= 35) {
            "android.permission.health.READ_HEART_RATE"
        } else {
            Manifest.permission.BODY_SENSORS
        }
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            Log.w(PassiveVitals.TAG, "${intent.action}: no heart rate permission, not registering")
            return
        }

        // A receiver may not outlive onReceive, so this is deliberately fire-and-forget on an
        // application-scoped context. Registration is a single IPC call to Health Services; if the
        // process dies first, the next app launch registers again.
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                Log.i(PassiveVitals.TAG, "${intent.action}: re-registering background vitals")
                val ok = PassiveVitals.register(appContext)
                PassiveVitalsStore(appContext).registered = ok
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Sent to the upgraded app itself, so a reinstall during development also restores
            // collection rather than appearing to work until the next reboot.
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
