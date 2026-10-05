package com.spencehouse.logue.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.schedule.DaySchedule
import com.spencehouse.logue.service.schedule.ScheduledChargeManager
import com.spencehouse.logue.service.schedule.WeeklyChargeSchedule
import com.spencehouse.logue.service.schedule.defaultWeeklySchedule
import com.spencehouse.logue.service.schedule.getNextEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject

enum class TimePickerType {
    START,
    STOP
}

enum class SchedulePreset {
    ALL_DAYS,
    WEEKDAYS,
    WEEKENDS,
    COPY_SUNDAY_TO_ALL
}

data class ScheduledChargingUiState(
    val vin: String = "",
    val vehicleName: String = "",
    val schedule: WeeklyChargeSchedule = WeeklyChargeSchedule(),
    val nextEventSummary: String? = null,
    val showTimePicker: Boolean = false,
    val activePickerDayOfWeek: Int? = null,
    val activePickerType: TimePickerType? = null,
    val pickerHour: Int = 20,
    val pickerMinute: Int = 0,
    val saveSuccess: Boolean = false,
)

@HiltViewModel
class ScheduledChargingViewModel @Inject constructor(
    private val authService: AuthService,
    private val scheduledChargeManager: ScheduledChargeManager,
) : ViewModel() {

    var uiState by mutableStateOf(ScheduledChargingUiState())
        private set

    init {
        loadCurrentVehicleSchedule()
    }

    fun loadCurrentVehicleSchedule() {
        val vin = authService.selectedVin ?: authService.sessionManager.vin.orEmpty()
        val vehicle = authService.getSelectedVehicle(vin)
        val name = vehicle?.aliasName?.takeIf { it.isNotEmpty() }
            ?: vehicle?.modelCode?.ifEmpty { "Clarity PHEV" }
            ?: "Clarity PHEV"

        val schedule = scheduledChargeManager.getSchedule(vin).copy(vin = vin)
        val summary = scheduledChargeManager.getScheduleSummary(vin)

        uiState = uiState.copy(
            vin = vin,
            vehicleName = name,
            schedule = schedule,
            nextEventSummary = summary,
        )
    }

    fun setScheduleEnabled(enabled: Boolean) {
        val updated = uiState.schedule.copy(isScheduleEnabled = enabled)
        uiState = uiState.copy(
            schedule = updated,
            nextEventSummary = if (enabled) updated.getNextEvent()?.let { scheduledChargeManager.getScheduleSummary(uiState.vin) } else null,
        )
    }

    fun setDayEnabled(dayOfWeek: Int, enabled: Boolean) {
        val updatedDays = uiState.schedule.days.map { day ->
            if (day.dayOfWeek == dayOfWeek) day.copy(enabled = enabled) else day
        }
        val updatedSchedule = uiState.schedule.copy(days = updatedDays)
        uiState = uiState.copy(schedule = updatedSchedule)
    }

    fun openTimePicker(dayOfWeek: Int, type: TimePickerType) {
        val day = uiState.schedule.days.find { it.dayOfWeek == dayOfWeek } ?: return
        val (h, m) = if (type == TimePickerType.START) {
            day.startHour to day.startMinute
        } else {
            day.stopHour to day.stopMinute
        }

        uiState = uiState.copy(
            showTimePicker = true,
            activePickerDayOfWeek = dayOfWeek,
            activePickerType = type,
            pickerHour = h,
            pickerMinute = m,
        )
    }

    fun dismissTimePicker() {
        uiState = uiState.copy(showTimePicker = false)
    }

    fun confirmTimePicker(hour: Int, minute: Int) {
        val dayOfWeek = uiState.activePickerDayOfWeek ?: return
        val type = uiState.activePickerType ?: return

        val updatedDays = uiState.schedule.days.map { day ->
            if (day.dayOfWeek == dayOfWeek) {
                if (type == TimePickerType.START) {
                    day.copy(startHour = hour, startMinute = minute)
                } else {
                    day.copy(stopHour = hour, stopMinute = minute)
                }
            } else {
                day
            }
        }

        uiState = uiState.copy(
            schedule = uiState.schedule.copy(days = updatedDays),
            showTimePicker = false,
        )
    }

    fun applyPreset(preset: SchedulePreset) {
        val currentDays = uiState.schedule.days
        val updatedDays = when (preset) {
            SchedulePreset.ALL_DAYS -> currentDays.map { it.copy(enabled = true) }
            SchedulePreset.WEEKDAYS -> currentDays.map { day ->
                val isWeekday = day.dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
                day.copy(enabled = isWeekday)
            }
            SchedulePreset.WEEKENDS -> currentDays.map { day ->
                val isWeekend = day.dayOfWeek == Calendar.SUNDAY || day.dayOfWeek == Calendar.SATURDAY
                day.copy(enabled = isWeekend)
            }
            SchedulePreset.COPY_SUNDAY_TO_ALL -> {
                val sun = currentDays.find { it.dayOfWeek == Calendar.SUNDAY }
                if (sun != null) {
                    currentDays.map { day ->
                        day.copy(
                            startHour = sun.startHour,
                            startMinute = sun.startMinute,
                            stopHour = sun.stopHour,
                            stopMinute = sun.stopMinute,
                        )
                    }
                } else {
                    currentDays
                }
            }
        }

        uiState = uiState.copy(
            schedule = uiState.schedule.copy(
                isScheduleEnabled = true,
                days = updatedDays,
            ),
        )
    }

    fun saveSchedule() {
        if (uiState.vin.isEmpty()) return
        scheduledChargeManager.saveSchedule(uiState.schedule)
        val summary = scheduledChargeManager.getScheduleSummary(uiState.vin)
        uiState = uiState.copy(
            nextEventSummary = summary,
            saveSuccess = true,
        )
    }

    fun resetSaveSuccess() {
        uiState = uiState.copy(saveSuccess = false)
    }
}
