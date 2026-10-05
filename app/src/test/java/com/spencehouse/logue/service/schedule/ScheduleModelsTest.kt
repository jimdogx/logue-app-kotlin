package com.spencehouse.logue.service.schedule

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ScheduleModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testDefaultWeeklySchedule() {
        val days = defaultWeeklySchedule()
        assertEquals(7, days.size)
        assertEquals(Calendar.SUNDAY, days[0].dayOfWeek)
        assertEquals(Calendar.MONDAY, days[1].dayOfWeek)
        assertEquals(Calendar.TUESDAY, days[2].dayOfWeek)
        assertEquals(Calendar.WEDNESDAY, days[3].dayOfWeek)
        assertEquals(Calendar.THURSDAY, days[4].dayOfWeek)
        assertEquals(Calendar.FRIDAY, days[5].dayOfWeek)
        assertEquals(Calendar.SATURDAY, days[6].dayOfWeek)
        assertTrue(days.all { !it.enabled })
        assertTrue(days.all { it.startHour == 20 && it.startMinute == 0 })
        assertTrue(days.all { it.stopHour == 16 && it.stopMinute == 0 })
    }

    @Test
    fun testGetNextEvent_disabledScheduleReturnsNull() {
        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = false,
            days = defaultWeeklySchedule().map { it.copy(enabled = true) },
        )
        assertNull(schedule.getNextEvent())
    }

    @Test
    fun testGetNextEvent_noEnabledDaysReturnsNull() {
        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = defaultWeeklySchedule(), // all false
        )
        assertNull(schedule.getNextEvent())
    }

    @Test
    fun testGetNextEvent_startEventUpcoming() {
        // Set fixed now: Sunday at 18:00 (6:00 PM)
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Sunday start at 20:00 (8:00 PM), stop at 16:00 (4:00 PM next day)
        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.SUNDAY) {
                it.copy(enabled = true, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0)
            } else it
        }

        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = days,
        )

        val nextEvent = schedule.getNextEvent(nowMillis)
        assertNotNull(nextEvent)
        assertEquals(ChargeAction.START, nextEvent!!.action)
        assertEquals(Calendar.SUNDAY, nextEvent.dayOfWeek)
        assertEquals(20, nextEvent.targetHour)
        assertEquals(0, nextEvent.targetMinute)
    }

    @Test
    fun testGetNextEvent_inProgressOvernightEventNextIsStop() {
        // Set fixed now: Sunday at 21:00 (9:00 PM)
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Sunday start at 20:00 (8:00 PM), stop at 16:00 (4:00 PM next day = Monday 4:00 PM)
        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.SUNDAY) {
                it.copy(enabled = true, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0)
            } else it
        }

        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = days,
        )

        val nextEvent = schedule.getNextEvent(nowMillis)
        assertNotNull(nextEvent)
        assertEquals(ChargeAction.STOP, nextEvent!!.action)
        assertEquals(Calendar.SUNDAY, nextEvent.dayOfWeek)
        assertEquals(16, nextEvent.targetHour)
        assertEquals(0, nextEvent.targetMinute)

        // Verify stop timestamp is Monday 16:00
        val eventCal = Calendar.getInstance().apply { timeInMillis = nextEvent.triggerTimeMillis }
        assertEquals(Calendar.MONDAY, eventCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(16, eventCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testGetNextEvent_yesterdayOvernightStopToday() {
        // Saturday 20:00 start, 04:00 stop (Sunday morning)
        // Now is Sunday at 02:00 AM
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.SATURDAY) {
                it.copy(enabled = true, startHour = 20, startMinute = 0, stopHour = 4, stopMinute = 0)
            } else it
        }

        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = days,
        )

        val nextEvent = schedule.getNextEvent(nowMillis)
        assertNotNull(nextEvent)
        assertEquals(ChargeAction.STOP, nextEvent!!.action)
        assertEquals(Calendar.SATURDAY, nextEvent.dayOfWeek)
        assertEquals(4, nextEvent.targetHour)

        // Event trigger should be Sunday 04:00
        val eventCal = Calendar.getInstance().apply { timeInMillis = nextEvent.triggerTimeMillis }
        assertEquals(Calendar.SUNDAY, eventCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(4, eventCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testGetNextEvent_sameDayStop() {
        // Monday 8:00 AM start, 4:00 PM (16:00) stop
        // Now is Monday 10:00 AM
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.MONDAY) {
                it.copy(enabled = true, startHour = 8, startMinute = 0, stopHour = 16, stopMinute = 0)
            } else it
        }

        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = days,
        )

        val nextEvent = schedule.getNextEvent(nowMillis)
        assertNotNull(nextEvent)
        assertEquals(ChargeAction.STOP, nextEvent!!.action)
        assertEquals(Calendar.MONDAY, nextEvent.dayOfWeek)
        assertEquals(16, nextEvent.targetHour)

        val eventCal = Calendar.getInstance().apply { timeInMillis = nextEvent.triggerTimeMillis }
        assertEquals(Calendar.MONDAY, eventCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(16, eventCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testFormatDurationText() {
        // 20:00 (8pm) to 16:00 (4pm next day) = 20 hours
        val text1 = formatDurationText(20, 0, 16, 0)
        assertEquals("20 hrs · Ends next day", text1)

        // 20:00 (8pm) to 4:00 (4am next day) = 8 hours
        val text2 = formatDurationText(20, 0, 4, 0)
        assertEquals("8 hrs · Ends next day", text2)

        // 8:00 to 16:00 (same day) = 8 hours
        val text3 = formatDurationText(8, 0, 16, 0)
        assertEquals("8 hrs · Same day", text3)
    }

    @Test
    fun testSerializationDeserialization() {
        val schedule = WeeklyChargeSchedule(
            vin = "1HGBF1E38JA000001",
            isScheduleEnabled = true,
            days = defaultWeeklySchedule().mapIndexed { index, day ->
                if (index == 0) day.copy(enabled = true, startHour = 20, stopHour = 16) else day
            },
        )

        val jsonStr = json.encodeToString(schedule)
        val decoded = json.decodeFromString<WeeklyChargeSchedule>(jsonStr)

        assertEquals(schedule.vin, decoded.vin)
        assertEquals(schedule.isScheduleEnabled, decoded.isScheduleEnabled)
        assertEquals(7, decoded.days.size)
        assertTrue(decoded.days[0].enabled)
        assertEquals(20, decoded.days[0].startHour)
        assertEquals(16, decoded.days[0].stopHour)
    }
}
