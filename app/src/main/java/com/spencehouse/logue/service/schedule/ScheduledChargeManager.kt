package com.spencehouse.logue.service.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.spencehouse.logue.MainActivity
import com.spencehouse.logue.R
import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.SessionManager
import com.spencehouse.logue.service.VehicleService
import com.spencehouse.logue.widget.BatteryWidget
import com.spencehouse.logue.widget.CommandsWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledChargeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val vehicleService: VehicleService,
    private val authService: AuthService,
    private val json: Json,
) {
    companion object {
        private const val TAG = "ScheduledChargeManager"
        private const val NOTIFICATION_CHANNEL_ID = "vehicle_status"
    }

    fun getSchedule(vin: String): WeeklyChargeSchedule {
        val jsonStr = sessionManager.getWeeklyChargeScheduleJson(vin) ?: return WeeklyChargeSchedule(
            vin = vin,
            isScheduleEnabled = false,
            days = defaultWeeklySchedule(),
        )
        return try {
            json.decodeFromString<WeeklyChargeSchedule>(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode schedule for $vin, returning default", e)
            WeeklyChargeSchedule(vin = vin, isScheduleEnabled = false, days = defaultWeeklySchedule())
        }
    }

    fun saveSchedule(schedule: WeeklyChargeSchedule) {
        Log.i(TAG, "Saving schedule for VIN ${schedule.vin}. Enabled: ${schedule.isScheduleEnabled}")
        val jsonStr = json.encodeToString(schedule)
        sessionManager.saveWeeklyChargeScheduleJson(schedule.vin, jsonStr)
        scheduleNextAlarm(schedule.vin)
    }

    fun cancelSchedule(vin: String) {
        Log.i(TAG, "Cancelling scheduled alarm for VIN $vin")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledChargeReceiver::class.java).apply {
            action = ScheduledChargeReceiver.ACTION_SCHEDULED_CHARGE
            putExtra(ScheduledChargeReceiver.EXTRA_VIN, vin)
        }
        val requestCode = (vin.hashCode() and 0x7FFFFFFF)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
    }

    fun scheduleNextAlarm(vin: String) {
        val schedule = getSchedule(vin)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduledChargeReceiver::class.java).apply {
            action = ScheduledChargeReceiver.ACTION_SCHEDULED_CHARGE
            putExtra(ScheduledChargeReceiver.EXTRA_VIN, vin)
        }
        val requestCode = (vin.hashCode() and 0x7FFFFFFF)
        val basePendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (!schedule.isScheduleEnabled) {
            Log.d(TAG, "Scheduled charging is disabled for $vin. Cancelling any pending alarm.")
            alarmManager.cancel(basePendingIntent)
            return
        }

        val nextEvent = schedule.getNextEvent()
        if (nextEvent == null) {
            Log.d(TAG, "No upcoming scheduled charge events found for $vin. Cancelling alarm.")
            alarmManager.cancel(basePendingIntent)
            return
        }

        intent.putExtra(ScheduledChargeReceiver.EXTRA_ACTION, nextEvent.action.name)
        val updatedPendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        Log.i(
            TAG,
            "Scheduling next charge wakeup for $vin: ${nextEvent.action} at ${Date(nextEvent.triggerTimeMillis)} (in ${(nextEvent.triggerTimeMillis - System.currentTimeMillis()) / 1000}s)",
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextEvent.triggerTimeMillis,
                        updatedPendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextEvent.triggerTimeMillis,
                        updatedPendingIntent,
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextEvent.triggerTimeMillis,
                    updatedPendingIntent,
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    nextEvent.triggerTimeMillis,
                    updatedPendingIntent,
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException setting exact alarm, falling back to inexact alarm", e)
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                nextEvent.triggerTimeMillis,
                updatedPendingIntent,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception scheduling alarm", e)
        }
    }

    suspend fun executeScheduledEvent(vin: String, action: ChargeAction): Result<String> {
        val vehicle = vehicleService.resolveVehicle(vin)
        val vehicleName = vehicle.aliasName?.takeIf { it.isNotEmpty() } ?: vehicle.modelCode.ifEmpty { "Clarity PHEV" }
        Log.i(TAG, "Executing scheduled $action charge event for $vehicleName ($vin)")

        if (authService.getSelectedVehicle(vin) == null) {
            Log.d(TAG, "Selected vehicle metadata not in memory, performing silent login")
            authService.login()
        }

        val result = if (action == ChargeAction.START) {
            vehicleService.startCharging(vehicle)
        } else {
            vehicleService.stopCharging(vehicle)
        }

        result.onSuccess {
            Log.i(TAG, "Scheduled $action charge successfully executed for $vin: $it")
            sendNotification(action, isSuccess = true, vehicleName = vehicleName)
        }.onFailure {
            Log.e(TAG, "Scheduled $action charge failed for $vin", it)
            sendNotification(
                action = action,
                isSuccess = false,
                errorMessage = it.message,
                vehicleName = vehicleName,
            )
        }

        // Re-schedule for the next upcoming event
        scheduleNextAlarm(vin)

        // Refresh widgets
        try {
            BatteryWidget().updateAll(context)
            CommandsWidget().updateAll(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update widgets after scheduled charge", e)
        }

        return result
    }

    fun getScheduleSummary(vin: String): String? {
        val schedule = getSchedule(vin)
        if (!schedule.isScheduleEnabled) return null
        val nextEvent = schedule.getNextEvent() ?: return "Active (no days enabled)"
        val actionText = if (nextEvent.action == ChargeAction.START) "Start" else "Stop"
        val cal = Calendar.getInstance().apply { timeInMillis = nextEvent.triggerTimeMillis }
        val timeStr = android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
        val dayStr = getShortDayName(cal.get(Calendar.DAY_OF_WEEK))
        return "Next: $dayStr $timeStr ($actionText)"
    }

    private fun sendNotification(
        action: ChargeAction,
        isSuccess: Boolean,
        errorMessage: String? = null,
        vehicleName: String = "Vehicle",
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val actionText = if (action == ChargeAction.START) "start" else "stop"
        val title = if (isSuccess) {
            "Scheduled Charging ${if (action == ChargeAction.START) "Started" else "Stopped"}"
        } else {
            "Scheduled Charging Failed"
        }
        val message = if (isSuccess) {
            "Successfully sent $actionText charging command for $vehicleName."
        } else {
            "Failed to $actionText charging for $vehicleName: ${errorMessage ?: "Unknown error"}"
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationId = if (action == ChargeAction.START) 1001 else 1002
        notificationManager.notify(notificationId, notification)
    }
}
