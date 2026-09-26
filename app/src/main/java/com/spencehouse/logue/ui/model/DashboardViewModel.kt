package com.spencehouse.logue.ui.model

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.VehicleService
import com.spencehouse.logue.service.WearableSyncManager
import com.spencehouse.logue.service.mqtt.AwsMqttClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val vehicleService: VehicleService,
    private val wearableSyncManager: WearableSyncManager,
) : ViewModel() {

    private val tag = "DashboardViewModel"
    var uiState by mutableStateOf(DashboardUiState())
        private set

    private var mqttClient: AwsMqttClient? = null
    private var refreshJob: Job? = null
    private var carFinderPollingJob: Job? = null
    private var carLocationPollingJob: Job? = null

    var isRefreshing by mutableStateOf(value = false)
        private set

    init {
        Log.d(tag, "Initializing DashboardViewModel")

        viewModelScope.launch {
            if (authService.vehicles.isEmpty()) {
                Log.d(tag, "Vehicles empty, attempting silent login")
                authService.login()
            }

            val mappedVehicles = authService.vehicles.map {
                VehicleUiModel(
                    vin = it.vin,
                    modelYear = it.modelYear,
                    divisionName = it.divisionName,
                    modelCode = it.modelCode,
                    aliasName = it.aliasName,
                    asset34FrontPath = it.asset34FrontPath,
                )
            }
            Log.d(tag, "Mapped vehicles in init: $mappedVehicles")

            val isEv = checkIfEv(authService.getVehicleName())
            uiState = uiState.copy(
                vehicleName = authService.getVehicleName(),
                vehicles = mappedVehicles,
                selectedVin = authService.selectedVin,
                isEv = isEv,
                useCelsius = authService.sessionManager.useCelsius,
                useKilometers = authService.sessionManager.useKilometers,
                useKpa = authService.sessionManager.useKpa,
                savedPin = authService.sessionManager.pin,
            )

            if (isEv) {
                connectMqtt()
            } else {
                updateStatus("Not an EV. OnStar must be active.")
            }
        }
        startAutoRefresh()
    }

    private fun checkIfEv(name: String): Boolean {
        val evModels = listOf("ZDX", "PROLOGUE", "EV", "CLARITY")
        return evModels.any { name.uppercase().contains(it) }
    }

    private fun connectMqtt() {
        val vin = authService.selectedVin
        if ((vin == null) || (!uiState.isEv)) return
        val vehicle = vehicleService.resolveVehicle(vin)

        if (!vehicle.isUltiumEv) {
            Log.d(tag, "Vehicle ${vehicle.vin} (${vehicle.modelCode}) uses HondaLink HTTP telematics; skipping MQTT")
            refreshData()
            return
        }

        viewModelScope.launch {
            try {
                Log.d(tag, "Connecting MQTT for VIN: ${vehicle.vin} (${vehicle.modelCode})")
                updateStatus("Authenticating MQTT...")
                val credsResult = vehicleService.getCigToken(vehicle)
                val creds = credsResult.getOrElse {
                    Log.e(tag, "Failed to get CIG token", it)
                    val errorMsg = it.message ?: ""
                    if (errorMsg.contains("Unable to resolve host")) {
                        updateStatus("Network Error. Check connection.")
                    } else if (errorMsg.contains("scope is invalid")) {
                        updateStatus("Not an EV. OnStar must be active.")
                    } else {
                        updateStatus("Auth Error: $errorMsg")
                    }
                    return@launch
                }

                Log.d(tag, "CIG Token received, initializing AwsMqttClient")
                updateStatus("Connecting to AWS IoT...")
                mqttClient?.disconnect()
                mqttClient = AwsMqttClient(
                    vin = vehicle.vin,
                    cigToken = creds.token,
                    cigSignature = creds.tokenSignature,
                    onMessageCallback = { topic, payload ->
                        Log.d(tag, "MQTT Message received on $topic")
                        onMqttMessage(topic, payload)
                    },
                    onConnected = {
                        Log.i(tag, "MQTT Connected successfully")
                        updateStatus("Connected")
                        refreshData()
                    },
                ) { error ->
                    Log.e(tag, "MQTT Client Error: $error")
                    updateStatus("Connection Error: $error")
                }
                mqttClient?.connect()
            } catch (e: Exception) {
                Log.e(tag, "connectMqtt exception", e)
                updateStatus("Connection Error: ${e.message}")
            }
        }
    }

    private fun onMqttMessage(topic: String, payload: String) {
        try {
            val json = JSONObject(payload)
            Log.v(tag, "Processing MQTT payload: $payload")
            if (topic.contains("DASHBOARD_ASYNC")) {
                updateDashboardUi(json)
            } else if (topic.contains("ENGINE_START_STOP_ASYNC")) {
                Log.d(tag, "Engine start/stop update received")
            } else if (topic.contains("CARFINDER_HORN_LIGHT_ASYNC")) {
                updateCarFinderUi(json)
            } else if (topic.contains("CARFINDER_LOCATION_ASYNC")) {
                updateVehicleLocationUi(json)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing MQTT message", e)
        }
    }

    private fun updateVehicleLocationUi(data: JSONObject) {
        val reported = data.optJSONObject("state")?.optJSONObject("reported") ?: data
        val rb = reported.optJSONObject("responseBody") ?: return

        Log.d(tag, "Updating UI with reported vehicle location data")
        val gpsData = rb.optJSONObject("gpsData")
        val coordinate = gpsData?.optJSONObject("coordinate")
        
        val latitude = coordinate?.optDouble("latitude") ?: Double.NaN
        val longitude = coordinate?.optDouble("longitude") ?: Double.NaN
        val timestamp = data.optLong("timestamp", System.currentTimeMillis() / 1000)

        if ((!latitude.isNaN()) && (!longitude.isNaN()) && (latitude != 0.0) && (longitude != 0.0)) {
            uiState = uiState.copy(
                vehicleLocation = VehicleLocation(latitude, longitude, timestamp),
            )
        }
    }

    private fun updateCarFinderUi(data: JSONObject) {
        val reported = data.optJSONObject("state")?.optJSONObject("reported") ?: data
        val rb = reported.optJSONObject("responseBody") ?: return

        Log.d(tag, "Updating UI with reported car finder data")
        val lightStatus = rb.optString("lightStatus", "OFF")
        val hornStatus = rb.optString("hornStatus", "OFF")

        uiState = uiState.copy(
            isFlashing = lightStatus == "ON",
            isHonking = hornStatus == "ON"
        )
    }

    private fun parseDmsOrDecimal(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        trimmed.toDoubleOrNull()?.let { return it }
        val sign = if (trimmed.startsWith("-")) -1.0 else 1.0
        val parts = trimmed.removePrefix("+").removePrefix("-").split(",")
        if (parts.size != 3) return null
        val deg = parts[0].toDoubleOrNull() ?: return null
        val min = parts[1].toDoubleOrNull() ?: return null
        val sec = parts[2].toDoubleOrNull() ?: return null
        return sign * (deg + (min / 60.0) + (sec / 3600.0))
    }

    private fun updateDashboardUi(data: JSONObject) {
        val reported = data.optJSONObject("state")?.optJSONObject("reported") ?: data
        val rb = reported.optJSONObject("responseBody") ?: return

        val keysList = rb.keys().asSequence().toList()
        Log.d(tag, "Updating UI with reported dashboard data. Keys: $keysList")
        val evStatus = rb.optJSONObject("evStatus")
        val vehicleInfo = evStatus?.optJSONObject("vehicleInfo")
        val fuelLevel = rb.optJSONObject("fuelLevel")
        val odometerData = rb.optJSONObject("odometer")
        val tireStatus = rb.optJSONObject("tireStatus")
        val chargeMode = rb.optJSONObject("getChargeMode")
        val chargeTime = rb.optJSONObject("hvBatteryChargeCompleteTime")

        val battery = vehicleInfo?.optJSONObject("soc")?.optString("value")?.toDoubleOrNull()?.roundToInt()
            ?: evStatus?.optDouble("soc")?.takeIf { !it.isNaN() }?.toInt()
            ?: rb.optJSONObject("batteryStatus")?.optDouble("soc")?.takeIf { !it.isNaN() }?.toInt()
            ?: rb.optJSONObject("hvBattery")?.optDouble("soc")?.takeIf { !it.isNaN() }?.toInt()
            ?: fuelLevel?.optJSONObject("currentLevel")?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt()

        val rangeVal = vehicleInfo?.optJSONObject("evRange")?.optString("value")?.toDoubleOrNull()?.roundToInt()
            ?: evStatus?.optDouble("evRange")?.takeIf { !it.isNaN() }?.toInt()
            ?: rb.optJSONObject("evRange")?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt()
            ?: rb.optJSONObject("evDriveRange")?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt()
            ?: fuelLevel?.optJSONObject("driveRange")?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt()

        val chargeStatus = vehicleInfo?.optJSONObject("chargeStatus")?.optString("value")?.takeIf { it.isNotEmpty() }
            ?: evStatus?.optString("chargeStatus")?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
            ?: evStatus?.optString("chgStatus")?.takeIf { it.isNotEmpty() }
        val plugStatus = vehicleInfo?.optJSONObject("plugStatus")?.optString("value")?.takeIf { it.isNotEmpty() }
            ?: evStatus?.optString("plugStatus")?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
            ?: evStatus?.optString("evPlugin")?.takeIf { it.isNotEmpty() }
        val chargeModeValue = vehicleInfo?.optJSONObject("chargeMode")?.takeIf { it.optBoolean("valid", false) }?.optString("value")?.takeIf { it.isNotEmpty() }
            ?: evStatus?.optString("chargeMode")?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
            ?: evStatus?.optString("chargerVoltage")?.takeIf { it.isNotEmpty() }

        val targetLevel = chargeMode?.optJSONObject("generalAwayTargetChargeLevel")?.optInt("value") ?: 80

        val isPluggedIn = (plugStatus?.lowercase() == "plugged") ||
            (plugStatus == "1") ||
            (chargeStatus?.lowercase() == "charging") ||
            (chargeStatus == "1")

        val (mainStatus, voltage) = formatChargeStatus(chargeStatus, plugStatus, chargeModeValue)

        val latRaw = vehicleInfo?.optJSONObject("curPosLat")?.takeIf { it.optBoolean("valid", false) }?.optString("value")
            ?: vehicleInfo?.optJSONObject("naviCurPosLat")?.takeIf { it.optBoolean("valid", false) }?.optString("value")
        val lonRaw = vehicleInfo?.optJSONObject("curPosLon")?.takeIf { it.optBoolean("valid", false) }?.optString("value")
            ?: vehicleInfo?.optJSONObject("naviCurPosLon")?.takeIf { it.optBoolean("valid", false) }?.optString("value")
        val parsedLat = parseDmsOrDecimal(latRaw)
        val parsedLon = parseDmsOrDecimal(lonRaw)
        val updatedLocation = if (parsedLat != null && parsedLon != null && parsedLat != 0.0 && parsedLon != 0.0) {
            VehicleLocation(parsedLat, parsedLon, System.currentTimeMillis() / 1000)
        } else {
            uiState.vehicleLocation
        }

        val acRaw = vehicleInfo?.optJSONObject("acStatus")?.takeIf { it.optBoolean("valid", false) }?.optString("value")
        val updatedClimate = when (acRaw?.lowercase()) {
            "1", "on" -> "ON"
            "0", "off" -> "OFF"
            else -> uiState.climateStatus
        }

        val odometerVal = odometerData?.optString("value")?.toDoubleOrNull()?.roundToInt()
            ?: odometerData?.optInt("value")?.takeIf { it > 0 }
            ?: uiState.odometer

        authService.sessionManager.cachedBatteryPercentage = battery ?: -1
        authService.sessionManager.cachedRange = rangeVal ?: -1
        authService.sessionManager.cachedChargeStatus = mainStatus
        authService.sessionManager.cachedIsPluggedIn = isPluggedIn
        authService.sessionManager.targetChargeLevel = targetLevel

        uiState = uiState.copy(
            batteryPercentage = battery ?: uiState.batteryPercentage,
            range = rangeVal ?: uiState.range,
            chargeStatus = mainStatus,
            chargeVoltage = voltage,
            chargeCompletionTime = formatTime(chargeTime),
            isPluggedIn = isPluggedIn,
            targetChargeLevel = targetLevel,
            odometer = odometerVal,
            tirePressures = if (tireStatus != null) parseTires(tireStatus) else uiState.tirePressures,
            vehicleLocation = updatedLocation,
            climateStatus = updatedClimate,
            lastUpdated = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date()),
            statusText = "Data Received"
        )

        val vin = authService.selectedVin
        if ((vin != null) && (battery != null) && (rangeVal != null)) {
            wearableSyncManager.syncVehicleTelemetry(
                vin,
                battery,
                rangeVal,
                mainStatus,
                targetLevel,
                isPluggedIn,
                authService.sessionManager.useCelsius,
                authService.sessionManager.useKilometers
            )
        }
    }

    private fun formatTime(chargeTime: JSONObject?): String? {
        if (chargeTime == null) return null

        val day = chargeTime.optJSONObject("hvBatteryChargeCompleteDay")?.optString("value")
        val hourStr = chargeTime.optJSONObject("hvBatteryChargeCompleteHour")?.optString("value")
        val minuteStr = chargeTime.optJSONObject("hvBatteryChargeCompleteMinute")?.optString("value")

        if (day.isNullOrEmpty() || hourStr.isNullOrEmpty() || minuteStr.isNullOrEmpty()) {
            return null
        }

        val hour = hourStr.toIntOrNull()
        val minute = minuteStr.toIntOrNull()

        if (hour == null || minute == null) return null

        val calendar = Calendar.getInstance()
        val currentDay = calendar[Calendar.DAY_OF_WEEK]

        val dayOfWeekMap = mapOf(
            "Sunday" to 1, "Monday" to 2, "Tuesday" to 3, "Wednesday" to 4,
            "Thursday" to 5, "Friday" to 6, "Saturday" to 7
        )

        val targetDay = dayOfWeekMap[day] ?: return null
        val daysToAdd = (targetDay - currentDay + 7) % 7

        calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
        calendar[Calendar.HOUR_OF_DAY] = hour
        calendar[Calendar.MINUTE] = minute
        calendar[Calendar.SECOND] = 0

        // If the calculated time is in the past (and it's the same day), assume it's for the next week
        if (daysToAdd == 0 && calendar.timeInMillis < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 7)
        }

        val dateFormat = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
        return dateFormat.format(calendar.time)
    }

    private fun formatChargeStatus(chargeStatus: String?, plugStatus: String?, chargeMode: String?): Pair<String, String?> {
        val status = chargeStatus?.lowercase()
        val pStatus = plugStatus?.lowercase()

        val isPluggedIn = (pStatus == "plugged") || (pStatus == "1") || (status == "charging") || (status == "1")

        if (!isPluggedIn) {
            return "Unplugged" to null
        }

        var mainStatus = "Plugged In"
        when (status) {
            "charging", "1" -> mainStatus = "Charging"
            "complete" -> mainStatus = "Complete"
        }

        val chargeModeInt = chargeMode?.toIntOrNull()
        val voltage = when {
            chargeModeInt == 1 -> "120V"
            chargeModeInt == 2 -> "240V"
            chargeModeInt != null && chargeModeInt > 2 -> "${chargeModeInt}V"
            else -> null
        }

        return mainStatus to voltage
    }

    private fun parseTires(tireStatus: JSONObject?): Map<String, Double?> {
        val tires = mutableMapOf<String, Double?>()
        val positions = listOf("frontLeft", "frontRight", "rearLeft", "rearRight")
        positions.forEach { pos ->
            tires[pos] = tireStatus?.optJSONObject(pos)?.optJSONObject("pressureData")?.optDouble("value")
        }
        return tires
    }

    fun refreshData(): Job {
        val vin = authService.selectedVin ?: return viewModelScope.launch {}
        val vehicle = vehicleService.resolveVehicle(vin)

        if (!uiState.isEv) return viewModelScope.launch {}

        return viewModelScope.launch {
            isRefreshing = true
            Log.d(tag, "Refreshing data and checking connection for VIN: ${vehicle.vin} (${vehicle.modelCode})")

            if (uiState.isEv) {
                // Reconnect if status indicates an error or disconnect (only for Ultium MQTT vehicles)
                if (vehicle.isUltiumEv && (uiState.statusText.contains("Error") || uiState.statusText.contains("lost"))) {
                    connectMqtt()
                }

                updateStatus("Requesting update...")
                val result = vehicleService.requestDashboardRefresh(vehicle)
                result.onSuccess { refreshResult ->
                    refreshResult.directPayloadJson?.let { payloadJson ->
                        try {
                            updateDashboardUi(JSONObject(payloadJson))
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to parse direct dashboard JSON", e)
                        }
                    }
                }.onFailure {
                    Log.e(tag, "Manual dashboard request failed", it)
                    val errorMsg = it.message ?: ""
                    if (errorMsg.contains("Unable to resolve host")) {
                        updateStatus("Network Error. Check connection.")
                    } else if (errorMsg.contains("scope is invalid") && vehicle.isUltiumEv) {
                        updateStatus("Not an EV. OnStar must be active.")
                        uiState = uiState.copy(isEv = false)
                    } else {
                        updateStatus("Refresh failed: $errorMsg")
                    }
                }
            } else {
                updateStatus("Not an EV. OnStar must be active.")
            }

            val climateResult = vehicleService.getClimateStatus(vehicle)
            climateResult.onSuccess {
                val status = it.jsonObject["climateStatus"]?.jsonPrimitive?.content ?: "OFF"
                Log.d(tag, "Climate status received: $status")
                authService.sessionManager.cachedClimateStatus = status.uppercase()
                val mappedVehicles = authService.vehicles.map { v ->
                    VehicleUiModel(
                        vin = v.vin,
                        modelYear = v.modelYear,
                        divisionName = v.divisionName,
                        modelCode = v.modelCode,
                        aliasName = v.aliasName,
                        asset34FrontPath = v.asset34FrontPath
                    )
                }
                Log.d(tag, "Mapped vehicles in refreshData: $mappedVehicles")
                uiState = uiState.copy(
                    climateStatus = status.uppercase(),
                    vehicles = mappedVehicles
                )
            }.onFailure {
                Log.e(tag, "Climate status request failed", it)
                val errorMsg = it.message ?: ""
                if (errorMsg.contains("Unable to resolve host")) {
                    updateStatus("Network Error. Check connection.")
                }
            }

            isRefreshing = false
        }
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(60.seconds)
                Log.d(tag, "Auto-refreshing data")
                refreshData()
            }
        }
    }

    fun toggleCelsius(value: Boolean) {
        authService.sessionManager.useCelsius = value
        uiState = uiState.copy(useCelsius = value)
    }

    fun toggleKilometers(value: Boolean) {
        authService.sessionManager.useKilometers = value
        uiState = uiState.copy(useKilometers = value)
    }

    fun toggleKpa(value: Boolean) {
        authService.sessionManager.useKpa = value
        uiState = uiState.copy(useKpa = value)
    }

    private fun updateStatus(text: String) {
        uiState = uiState.copy(statusText = text)
    }

    fun onVehicleChange(vin: String) {
        if (vin == authService.selectedVin) return

        Log.i(tag, "Changing vehicle to VIN: $vin")
        mqttClient?.disconnect()

        // This was the missing link - update persistent storage!
        authService.updateSelectedVin(vin)

        val mappedVehicles = authService.vehicles.map {
            VehicleUiModel(
                vin = it.vin,
                modelYear = it.modelYear,
                divisionName = it.divisionName,
                modelCode = it.modelCode,
                aliasName = it.aliasName,
                asset34FrontPath = it.asset34FrontPath
            )
        }
        Log.d(tag, "Mapped vehicles in onVehicleChange: $mappedVehicles")

        val isEv = checkIfEv(authService.getVehicleName())
        uiState = uiState.copy(
            selectedVin = vin,
            vehicleName = authService.getVehicleName(),
            isEv = isEv,
            batteryPercentage = null,
            range = null,
            statusText = if (isEv) "Switching vehicles..." else "Not an EV. OnStar must be active.",
            vehicles = mappedVehicles
        )

        if (isEv) {
            connectMqtt()
        }
    }

    fun logout() {
        Log.i(tag, "User logged out from Dashboard")
        mqttClient?.disconnect()
        authService.logout()
    }

    fun setTargetChargeLevel(level: Int) {
        val vin = authService.selectedVin ?: return
        if (!uiState.isEv) return
        val vehicle = vehicleService.resolveVehicle(vin)

        viewModelScope.launch {
            Log.d(tag, "Setting target charge level to $level%")
            vehicleService.setTargetChargeLevel(vehicle, level)
            refreshData()
        }
    }

    fun setPin(pin: String?) {
        authService.sessionManager.pin = pin
        uiState = uiState.copy(savedPin = pin)
    }

    private suspend fun sendCommand(name: String, action: suspend (String) -> Result<String?>, pin: String): Result<String?> {
        Log.i(tag, "Sending command: $name")
        updateStatus("Sending $name command...")
        val result = action(pin)
        result.onSuccess {
            Log.i(tag, "Command $name sent successfully")
            updateStatus("$name command sent!")
        }.onFailure {
            Log.e(tag, "Command $name failed", it)
            updateStatus("$name failed: ${it.message}")
        }
        return result
    }

    fun requestVehicleLocation(pin: String) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        viewModelScope.launch {
            val result = sendCommand(
                "Vehicle Location",
                { p -> vehicleService.requestVehicleLocation(vehicle, p) },
                pin
            )
            result.onSuccess {
                startCarLocationPolling()
            }.onFailure {
                uiState = uiState.copy(vehicleLocationError = it.message)
            }
        }
    }

    private fun startCarLocationPolling() {
        carLocationPollingJob?.cancel()
        carLocationPollingJob = viewModelScope.launch {
            uiState = uiState.copy(vehicleLocationError = null)
            Log.d(tag, "Starting car location polling")
            for (i in 1..12) {
                if (!isActive) return@launch
                updateStatus("Requesting location... (attempt $i)")

                if (uiState.vehicleLocation != null) {
                    Log.i(tag, "Vehicle location found after $i polls")
                    updateStatus("Vehicle location found.")
                    cancel()
                    return@launch
                }
                delay(5.seconds)
                refreshData()
            }
            Log.w(tag, "Car location polling timed out")
            updateStatus("Failed to get vehicle location.")
        }
    }

    private fun sendCarFinderCommand(name: String, action: suspend (String) -> Result<String?>, pin: String, targetIsOff: Boolean = false) {
        viewModelScope.launch {
            val result = sendCommand(name, action, pin)
            result.onSuccess {
                startCarFinderPolling(targetIsOff)
            }
        }
    }

    private fun startCarFinderPolling(targetIsOff: Boolean) {
        carFinderPollingJob?.cancel()
        carFinderPollingJob = viewModelScope.launch {
            Log.d(tag, "Starting car finder polling for targetIsOff: $targetIsOff")
            for (i in 1..12) { // Increased polling attempts
                if (!isActive) return@launch
                updateStatus("Checking status... (attempt $i)")

                val conditionMet = if (targetIsOff) {
                    !uiState.isFlashing && !uiState.isHonking
                } else {
                    uiState.isFlashing || uiState.isHonking
                }

                if (conditionMet) {
                    if (targetIsOff) {
                        Log.i(tag, "Target status (OFF) reached after $i polls")
                        updateStatus("Lights and Horn are off.")
                    } else {
                        Log.i(tag, "Target status (ON) reached after $i polls")
                        when {
                            uiState.isFlashing && uiState.isHonking -> updateStatus("Lights and Horn are on.")
                            uiState.isFlashing -> updateStatus("Lights are turning on.")
                            else -> updateStatus("Horn is turning on.")
                        }
                    }
                    cancel()
                    return@launch
                }
                delay(5.seconds) // Increased delay
                refreshData() // Actively refresh data
            }
            Log.w(tag, "Car finder polling timed out")
            updateStatus("Failed to get status.")
        }
    }

    private fun sendCommandWithPolling(name: String, action: suspend (String) -> Result<String?>, pin: String, targetStatus: String) {
        viewModelScope.launch {
            Log.i(tag, "Sending command: $name")
            updateStatus("Sending $name command...")
            val result = action(pin)
            result.onSuccess {
                Log.i(tag, "Command $name sent successfully")
                updateStatus("$name command sent!")
                
                if (name == "Start Climate") {
                    val intent = Intent(context, com.spencehouse.logue.service.ClimateControlService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
                
                startAggressivePolling(targetStatus)
            }.onFailure {
                Log.e(tag, "Command $name failed", it)
                updateStatus("$name failed: ${it.message}")
            }
        }
    }

    fun startClimate(pin: String, temp: Int) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        sendCommandWithPolling(
            "Start Climate",
            { p -> vehicleService.startClimate(vehicle, p, temp) },
            pin,
            "ON"
        )
    }

    fun stopClimate(pin: String) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        sendCommandWithPolling(
            "Stop Climate",
            { p -> vehicleService.stopClimate(vehicle, p) },
            pin,
            "OFF"
        )
    }

    fun toggleFlashLights(pin: String) {
        if (uiState.isFlashing) {
            stopFlashAndHorn(pin)
        } else {
            flashLights(pin)
        }
    }

    fun toggleSoundHorn(pin: String) {
        if (uiState.isHonking) {
            stopFlashAndHorn(pin)
        } else {
            soundHorn(pin)
        }
    }

    fun flashLights(pin: String) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        uiState = uiState.copy(isFlashing = true)
        sendCarFinderCommand(
            "Flash Lights",
            { p -> vehicleService.requestLightHorn(vehicle, p, "lgt") },
            pin
        )
    }

    fun soundHorn(pin: String) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        uiState = uiState.copy(isHonking = true)
        sendCarFinderCommand(
            "Sound Horn",
            { p -> vehicleService.requestLightHorn(vehicle, p, "hrn") },
            pin
        )
    }

    fun stopFlashAndHorn(pin: String) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        uiState = uiState.copy(isFlashing = false, isHonking = false)
        sendCarFinderCommand(
            "Stop Flash and Horn",
            { p -> vehicleService.requestStopLightHorn(vehicle, p) },
            pin,
            targetIsOff = true
        )
    }

    fun lockDoors(pin: String) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        viewModelScope.launch {
            sendCommand("Lock Doors", { p ->
                vehicleService.requestDoorLock(vehicle, p, "alk")
            }, pin)
        }
    }

    fun unlockDoors(pin: String) {
        val vehicle = vehicleService.resolveVehicle(authService.selectedVin ?: return)
        viewModelScope.launch {
            sendCommand("Unlock Doors", { p ->
                vehicleService.requestDoorLock(vehicle, p, "dulk")
            }, pin)
        }
    }

    override fun onCleared() {
        Log.d(tag, "DashboardViewModel cleared, disconnecting MQTT")
        mqttClient?.disconnect()
        refreshJob?.cancel()
        carFinderPollingJob?.cancel()
        carLocationPollingJob?.cancel()
        super.onCleared()
    }

    private fun startAggressivePolling(targetStatus: String) {
        viewModelScope.launch {
            Log.d(tag, "Starting aggressive polling for target status: $targetStatus")
            for (i in 1..12) {
                if (!isActive) return@launch
                if (uiState.climateStatus == targetStatus) {
                    Log.i(tag, "Target status reached after $i polls")
                    updateStatus("Climate is ${targetStatus.lowercase()}.")
                    cancel()
                }
                delay(5.seconds)
                refreshData()
            }
        }
    }
}
