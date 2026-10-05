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
        assertTrue(days.all { it.startEnabled && it.stopEnabled })
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
    fun testGetNextEvent_startEventUpcomingToday() {
        // Set fixed now: Monday at 18:00 (6:00 PM)
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Monday start at 20:00 (8:00 PM), stop at 16:00 (4:00 PM)
        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.MONDAY) {
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
        assertEquals(Calendar.MONDAY, nextEvent.dayOfWeek)
        assertEquals(20, nextEvent.targetHour)
        assertEquals(0, nextEvent.targetMinute)
    }

    @Test
    fun testGetNextEvent_independentStartAndStop_nextIsTomorrowStop() {
        // Current time: Monday at 21:00 (9:00 PM - after 8pm start)
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Monday and Tuesday both have Start 20:00 (8:00 PM) and Stop 16:00 (4:00 PM)
        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.MONDAY || it.dayOfWeek == Calendar.TUESDAY) {
                it.copy(enabled = true, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0)
            } else it
        }

        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = days,
        )

        // Since Monday's 16:00 and 20:00 have passed, the very next event must be Tuesday 16:00 (STOP)!
        val nextEvent = schedule.getNextEvent(nowMillis)
        assertNotNull(nextEvent)
        assertEquals(ChargeAction.STOP, nextEvent!!.action)
        assertEquals(Calendar.TUESDAY, nextEvent.dayOfWeek)
        assertEquals(16, nextEvent.targetHour)
        assertEquals(0, nextEvent.targetMinute)

        val eventCal = Calendar.getInstance().apply { timeInMillis = nextEvent.triggerTimeMillis }
        assertEquals(Calendar.TUESDAY, eventCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(16, eventCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testGetNextEvent_independentStartAndStop_afterStopNextIsStart() {
        // Current time: Tuesday at 16:05 (4:05 PM - after Tuesday 4pm stop)
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY)
            set(Calendar.HOUR_OF_DAY, 16)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.TUESDAY) {
                it.copy(enabled = true, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0)
            } else it
        }

        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = days,
        )

        // After 4:05 PM on Tuesday, the next event is Tuesday 20:00 (START)!
        val nextEvent = schedule.getNextEvent(nowMillis)
        assertNotNull(nextEvent)
        assertEquals(ChargeAction.START, nextEvent!!.action)
        assertEquals(Calendar.TUESDAY, nextEvent.dayOfWeek)
        assertEquals(20, nextEvent.targetHour)
    }

    @Test
    fun testGetNextEvent_onlyStartOrOnlyStopEnabled() {
        // Current time: Wednesday at 10:00 AM
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = nowCal.timeInMillis

        // Stop disabled, only Start enabled at 20:00
        val days = defaultWeeklySchedule().map {
            if (it.dayOfWeek == Calendar.WEDNESDAY) {
                it.copy(enabled = true, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = false)
            } else it
        }

        val schedule = WeeklyChargeSchedule(
            vin = "VIN123",
            isScheduleEnabled = true,
            days = days,
        )

        // 16:00 stop should be ignored since stopEnabled = false, so next is 20:00 START
        val nextEvent = schedule.getNextEvent(nowMillis)
        assertNotNull(nextEvent)
        assertEquals(ChargeAction.START, nextEvent!!.action)
        assertEquals(20, nextEvent.targetHour)
    }

    @Test
    fun testFormatDurationText() {
        val text1 = formatDurationText(20, 0, 16, 0)
        assertEquals("20 hrs window · Stops next day", text1)

        val text2 = formatDurationText(20, 0, 4, 0)
        assertEquals("8 hrs window · Stops next day", text2)

        val text3 = formatDurationText(8, 0, 16, 0)
        assertEquals("8 hrs window · Same day", text3)
    }

    @Test
    fun testSerializationDeserialization() {
        val schedule = WeeklyChargeSchedule(
            vin = "1HGBF1E38JA000001",
            isScheduleEnabled = true,
            days = defaultWeeklySchedule().mapIndexed { index, day ->
                if (index == 0) day.copy(enabled = true, startHour = 20, stopHour = 16, startEnabled = true, stopEnabled = false) else day
            },
        )

        val jsonStr = json.encodeToString(schedule)
        val decoded = json.decodeFromString<WeeklyChargeSchedule>(jsonStr)

        assertEquals(schedule.vin, decoded.vin)
        assertEquals(schedule.isScheduleEnabled, decoded.isScheduleEnabled)
        assertTrue(decoded.notificationsEnabled)
        assertEquals(7, decoded.days.size)
        assertTrue(decoded.days[0].enabled)
        assertEquals(20, decoded.days[0].startHour)
        assertEquals(16, decoded.days[0].stopHour)
        assertTrue(decoded.days[0].startEnabled)
        assertFalse(decoded.days[0].stopEnabled)

        // Test with notifications disabled
        val disabledNotifSchedule = schedule.copy(notificationsEnabled = false)
        val decodedDisabledNotif = json.decodeFromString<WeeklyChargeSchedule>(json.encodeToString(disabledNotifSchedule))
        assertFalse(decodedDisabledNotif.notificationsEnabled)
    }
}
