package com.spencehouse.logue.service.schedule

import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
enum class ChargeAction {
    START,
    STOP
}

@Serializable
data class DaySchedule(
    val dayOfWeek: Int, // Calendar.SUNDAY (1) through Calendar.SATURDAY (7)
    val enabled: Boolean = false,
    val startHour: Int = 20, // 8:00 PM default
    val startMinute: Int = 0,
    val stopHour: Int = 16,  // 4:00 PM default
    val stopMinute: Int = 0,
)

@Serializable
data class WeeklyChargeSchedule(
    val vin: String = "",
    val isScheduleEnabled: Boolean = false,
    val days: List<DaySchedule> = defaultWeeklySchedule(),
)

data class ScheduledChargeEvent(
    val action: ChargeAction,
    val triggerTimeMillis: Long,
    val dayOfWeek: Int,
    val targetHour: Int,
    val targetMinute: Int,
)

fun defaultWeeklySchedule(): List<DaySchedule> = listOf(
    DaySchedule(dayOfWeek = Calendar.SUNDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0),
    DaySchedule(dayOfWeek = Calendar.MONDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0),
    DaySchedule(dayOfWeek = Calendar.TUESDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0),
    DaySchedule(dayOfWeek = Calendar.WEDNESDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0),
    DaySchedule(dayOfWeek = Calendar.THURSDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0),
    DaySchedule(dayOfWeek = Calendar.FRIDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0),
    DaySchedule(dayOfWeek = Calendar.SATURDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0),
)

fun getDayName(dayOfWeek: Int): String = when (dayOfWeek) {
    Calendar.SUNDAY -> "Sunday"
    Calendar.MONDAY -> "Monday"
    Calendar.TUESDAY -> "Tuesday"
    Calendar.WEDNESDAY -> "Wednesday"
    Calendar.THURSDAY -> "Thursday"
    Calendar.FRIDAY -> "Friday"
    Calendar.SATURDAY -> "Saturday"
    else -> "Unknown"
}

fun getShortDayName(dayOfWeek: Int): String = when (dayOfWeek) {
    Calendar.SUNDAY -> "Sun"
    Calendar.MONDAY -> "Mon"
    Calendar.TUESDAY -> "Tue"
    Calendar.WEDNESDAY -> "Wed"
    Calendar.THURSDAY -> "Thu"
    Calendar.FRIDAY -> "Fri"
    Calendar.SATURDAY -> "Sat"
    else -> ""
}

fun formatDurationText(startHour: Int, startMinute: Int, stopHour: Int, stopMinute: Int): String {
    val startTotalMinutes = startHour * 60 + startMinute
    val stopTotalMinutes = stopHour * 60 + stopMinute
    val isNextDay = stopTotalMinutes <= startTotalMinutes
    val durationMinutes = if (isNextDay) {
        (24 * 60 - startTotalMinutes) + stopTotalMinutes
    } else {
        stopTotalMinutes - startTotalMinutes
    }
    val hours = durationMinutes / 60
    val minutes = durationMinutes % 60
    val durationStr = when {
        hours == 0 && minutes == 0 -> "24 hrs"
        minutes == 0 -> "$hours hrs"
        hours == 0 -> "$minutes mins"
        else -> "$hours hrs $minutes mins"
    }
    return if (isNextDay) {
        "$durationStr · Ends next day"
    } else {
        "$durationStr · Same day"
    }
}

/**
 * Calculates the next upcoming scheduled charge event (START or STOP).
 * Returns null if the schedule is disabled or no enabled days have upcoming events.
 */
fun WeeklyChargeSchedule.getNextEvent(nowMillis: Long = System.currentTimeMillis()): ScheduledChargeEvent? {
    if (!isScheduleEnabled || days.none { it.enabled }) return null

    val candidates = mutableListOf<ScheduledChargeEvent>()
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }

    // Check from -1 (yesterday, in case an overnight charge from yesterday stops today) through 7 days ahead
    for (offset in -1..7) {
        val checkCal = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, offset)
        }
        val checkDayOfWeek = checkCal.get(Calendar.DAY_OF_WEEK)
        val daySchedule = days.find { it.dayOfWeek == checkDayOfWeek && it.enabled } ?: continue

        // 1. Start event
        val startCal = (checkCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, daySchedule.startHour)
            set(Calendar.MINUTE, daySchedule.startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (startCal.timeInMillis > nowMillis) {
            candidates.add(
                ScheduledChargeEvent(
                    action = ChargeAction.START,
                    triggerTimeMillis = startCal.timeInMillis,
                    dayOfWeek = checkDayOfWeek,
                    targetHour = daySchedule.startHour,
                    targetMinute = daySchedule.startMinute,
                )
            )
        }

        // 2. Stop event
        val isOvernight = (daySchedule.stopHour < daySchedule.startHour) ||
                (daySchedule.stopHour == daySchedule.startHour && daySchedule.stopMinute <= daySchedule.startMinute)

        val stopCal = (checkCal.clone() as Calendar).apply {
            if (isOvernight) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            set(Calendar.HOUR_OF_DAY, daySchedule.stopHour)
            set(Calendar.MINUTE, daySchedule.stopMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (stopCal.timeInMillis > nowMillis) {
            candidates.add(
                ScheduledChargeEvent(
                    action = ChargeAction.STOP,
                    triggerTimeMillis = stopCal.timeInMillis,
                    dayOfWeek = checkDayOfWeek,
                    targetHour = daySchedule.stopHour,
                    targetMinute = daySchedule.stopMinute,
                )
            )
        }
    }

    return candidates.minByOrNull { it.triggerTimeMillis }
}
