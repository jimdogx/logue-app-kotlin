package com.spencehouse.logue.service.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.spencehouse.logue.di.ScheduledChargeEntryPoint
import dagger.hilt.android.EntryPointAccessors

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "Device booted or app replaced ($action). Restoring active scheduled charge alarms.")
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ScheduledChargeEntryPoint::class.java,
                )
                val authService = entryPoint.authService()
                val manager = entryPoint.scheduledChargeManager()

                val vin = authService.selectedVin ?: authService.sessionManager.vin
                if (!vin.isNullOrEmpty()) {
                    Log.i(TAG, "Restoring charge alarm for VIN: $vin")
                    manager.scheduleNextAlarm(vin)
                } else {
                    Log.d(TAG, "No active VIN found on boot to restore alarms.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore scheduled alarms on boot", e)
            }
        }
    }
}
