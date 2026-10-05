package com.spencehouse.logue.service.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.spencehouse.logue.di.ScheduledChargeEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduledChargeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledChargeReceiver"
        const val ACTION_SCHEDULED_CHARGE = "com.spencehouse.logue.action.SCHEDULED_CHARGE"
        const val EXTRA_VIN = "extra_vin"
        const val EXTRA_ACTION = "extra_action"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_SCHEDULED_CHARGE) return

        val vin = intent.getStringExtra(EXTRA_VIN)
        val actionStr = intent.getStringExtra(EXTRA_ACTION)

        if (vin.isNullOrEmpty() || actionStr.isNullOrEmpty()) {
            Log.e(TAG, "Missing VIN ($vin) or action ($actionStr)")
            return
        }

        val action = try {
            ChargeAction.valueOf(actionStr)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid charge action: $actionStr", e)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "logue:ScheduledChargeWakeLock",
        ).apply {
            acquire(60_000L) // 60s maximum timeout
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Scheduled charge alarm fired! Executing $action for VIN: $vin")
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ScheduledChargeEntryPoint::class.java,
                )
                val manager = entryPoint.scheduledChargeManager()
                manager.executeScheduledEvent(vin, action)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing scheduled charge alarm", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
