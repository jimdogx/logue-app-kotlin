package com.spencehouse.logue.service.telematics

import android.util.Log
import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.Config
import com.spencehouse.logue.service.DashboardData
import com.spencehouse.logue.service.SessionManager
import com.spencehouse.logue.service.mqtt.AwsMqttClient
import com.spencehouse.logue.service.remote.HondaWscApi
import com.spencehouse.logue.service.remote.dto.AcSetting
import com.spencehouse.logue.service.remote.dto.CigTokenRequest
import com.spencehouse.logue.service.remote.dto.CigTokenResponseBody
import com.spencehouse.logue.service.remote.dto.ClimateRequest
import com.spencehouse.logue.service.remote.dto.DashboardRequest
import com.spencehouse.logue.service.remote.dto.RemoteCommandRequest
import com.spencehouse.logue.service.remote.dto.TargetChargeLevelRequest
import com.spencehouse.logue.service.remote.dto.Vehicle
import com.spencehouse.logue.service.remote.dto.VehicleControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class MqttDashboardResponse(
    val state: MqttDashboardState,
)

@Serializable
private data class MqttDashboardState(
    val reported: MqttDashboardReported,
)

@Serializable
private data class MqttDashboardReported(
    val responseBody: ResponseBody,
)

@Serializable
private data class ResponseBody(
    val evStatus: EvStatus,
)

@Serializable
private data class EvStatus(
    val soc: String,
    val evRange: String,
    val chargerVoltage: String? = null,
    @SerialName("chgStatus")
    val chargingStatus: String? = null,
    @SerialName("evPlugin")
    val isPluggedIn: String? = null,
)

@Serializable
private data class ErrorResponse(
    val status: String,
    val responseBody: ErrorResponseBody,
)

@Serializable
private data class ErrorResponseBody(
    @SerialName("cigServiceRequestId")
    val cigServiceRequestId: String?,
    val errorCode: String,
    val errorMessage: String,
)

/**
 * Telematics handler for GM OnStar Ultium EVs (Acura ZDX, Honda Prologue).
 * Preserves the exact original endpoints, headers, and payloads for Ultium vehicles.
 */
@Singleton
class UltiumEvTelematicsHandler @Inject constructor(
    private val wscApi: HondaWscApi,
    private val authService: AuthService,
    private val sessionManager: SessionManager,
    private val json: Json,
) : VehicleTelematicsHandler {

    private val tolerantJson = Json { ignoreUnknownKeys = true }

    private suspend fun getHeaders(
        vehicle: Vehicle,
        siteId: String,
        version: String = "1.0",
        messageId: String = UUID.randomUUID().toString().uppercase(),
    ): Result<Map<String, String>> {
        authService.ensureAuthForVehicle(vehicle).onFailure { return Result.failure(it) }
        val accessToken = sessionManager.accessToken ?: return Result.failure(Exception("No access token"))
        val hidasIdent = sessionManager.hidasIdent ?: return Result.failure(Exception("No HIDAS ident"))

        return Result.success(
            Config.COMMON_HEADERS.toMutableMap().apply {
                put("Authorization", "Bearer $accessToken")
                put("hondaHeaderType.version", version)
                put("hondaHeaderType.siteId", siteId)
                put("hondaHeaderType.messageId", messageId)
                put("hondaHeaderType.systemId", "com.honda.hondalink.cv_android")
                put("hondaHeaderType.userId", hidasIdent)
                put("hondaHeaderType.hidasId", hidasIdent)
                put("hondaHeaderType.clientType", "Mobile")
                put(
                    "hondaHeaderType.collectedTimeStamp",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
                )
                put("Content-Type", "application/json")
                put("Accept", "application/json")
            },
        )
    }

    override suspend fun getDashboardData(vehicle: Vehicle): Result<DashboardData> =
        suspendCancellableCoroutine { continuation ->
            val vin = vehicle.vin
            var mqttClient: AwsMqttClient? = null
            val job = Job()
            val scope = CoroutineScope(Dispatchers.IO + job)

            continuation.invokeOnCancellation {
                job.cancel()
                mqttClient?.disconnect()
            }

            val onMessageCallback: (String, String) -> Unit = label@{ topic, payload ->
                if (topic.contains("DASHBOARD_ASYNC/update/accepted")) {
                    if (!continuation.isActive) return@label
                    try {
                        Log.d("UltiumEvTelematics.getDashboardData", "Processing MQTT payload: $payload")
                        val response = tolerantJson.decodeFromString<MqttDashboardResponse>(payload)
                        val evStatus = response.state.reported.responseBody.evStatus

                        val batteryLevel = evStatus.soc.toIntOrNull()
                        val range = evStatus.evRange.toDoubleOrNull()?.roundToInt()
                        val voltage = evStatus.chargerVoltage?.toIntOrNull()
                        val isPluggedIn = evStatus.isPluggedIn == "1"

                        batteryLevel?.let { sessionManager.cachedBatteryPercentage = it }
                        range?.let { sessionManager.cachedRange = it }
                        voltage?.let { sessionManager.cachedVoltage = it }
                        evStatus.chargingStatus?.let { sessionManager.cachedChargeStatus = it }
                        sessionManager.cachedIsPluggedIn = isPluggedIn

                        if ((batteryLevel != null) && (range != null)) {
                            val dashboardData = DashboardData(
                                batteryPercentage = batteryLevel,
                                range = range,
                            )
                            if (continuation.isActive) continuation.resume(Result.success(dashboardData))
                        } else {
                            Log.w("UltiumEvTelematics.getDashboardData", "Could not parse battery or range from payload")
                        }
                    } catch (e: Exception) {
                        Log.e("UltiumEvTelematics.getDashboardData", "Failed to parse MQTT payload", e)
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }

            val onConnected: () -> Unit = {
                scope.launch {
                    requestDashboard(vehicle).onFailure {
                        if (continuation.isActive) continuation.resumeWithException(it)
                    }
                }
            }

            val onError: (String) -> Unit = {
                if (continuation.isActive) continuation.resumeWithException(Exception(it))
            }

            scope.launch {
                getCigToken(vehicle).onSuccess { cigToken ->
                    val client = AwsMqttClient(
                        vin = vin,
                        cigToken = cigToken.token,
                        cigSignature = cigToken.tokenSignature,
                        onMessageCallback = onMessageCallback,
                        onConnected = onConnected,
                        onError = onError,
                    )
                    mqttClient = client
                    client.connect()
                }.onFailure {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
            }
        }

    override suspend fun getCigToken(vehicle: Vehicle): Result<CigTokenResponseBody> {
        val vin = vehicle.vin
        val tag = "VehicleService.CIG"
        var attempt = 0
        while (attempt < 3) {
            try {
                Log.d(tag, "Fetching CIG Token for VIN: $vin, Attempt: ${attempt + 1}")
                val headers = getHeaders(vehicle, siteId = "b407a3025b374f668475e97d2e750816").getOrElse {
                    return Result.failure(it)
                }
                val resp = wscApi.getCigToken(headers, CigTokenRequest(vin))
                val body = resp.body()
                if (resp.isSuccessful && body?.status == "Success") {
                    Log.d(tag, "Successfully acquired CIG Token")
                    return Result.success(body.responseBody)
                } else {
                    val errorBody = resp.errorBody()?.string()
                    Log.e(tag, "Failed CIG Token request. Code: ${resp.code()}, Error: $errorBody")
                    if (resp.code() == 400 && attempt < 2) {
                        Log.d(tag, "Retrying after 1 second")
                        delay(1.seconds)
                    } else {
                        return Result.failure(Exception("Failed to get CIG token: $errorBody"))
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception during getCigToken", e)
                if (attempt >= 2) return Result.failure(e)
            }
            attempt++
        }
        return Result.failure(Exception("Failed to get CIG token after 3 attempts"))
    }

    override suspend fun requestDashboard(vehicle: Vehicle): Result<DashboardRefreshResult> {
        val vin = vehicle.vin
        val tag = "VehicleService.DashboardReq"
        return try {
            Log.d(tag, "Requesting Dashboard update for VIN: $vin")
            val headersResult = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "I-13")
            if (headersResult.isFailure) {
                return Result.failure(headersResult.exceptionOrNull()!!)
            }
            val headers = headersResult.getOrThrow()
            val resp = wscApi.requestDashboard(headers, DashboardRequest(vin, Config.DASHBOARD_FILTERS))
            val body = resp.body()
            if (resp.isSuccessful && body?.status == "success") {
                val reqId = body.responseBody.cigServiceRequestId
                Log.d(tag, "Successfully requested Dashboard update. CIG Request ID: $reqId")
                Result.success(DashboardRefreshResult(requestId = reqId))
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed Dashboard request. Code: ${resp.code()}, Error: $errorBody")
                if (errorBody != null) {
                    try {
                        val error = json.decodeFromString<ErrorResponse>(errorBody)
                        return Result.failure(Exception(error.responseBody.errorMessage))
                    } catch (e: Exception) {
                        Log.w(tag, "Could not parse error body", e)
                    }
                }
                Result.failure(Exception("Dashboard request failed"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during requestDashboard", e)
            Result.failure(e)
        }
    }

    override suspend fun startClimate(vehicle: Vehicle, pin: String, temperature: Int): Result<String> {
        val vin = vehicle.vin
        val tag = "VehicleService.StartClimate"
        return try {
            Log.d(tag, "Starting climate for VIN: $vin")
            val headers = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val request = ClimateRequest(
                device = vin,
                extend = false,
                pin = pin,
                vehicleControl = VehicleControl(AcSetting("autoOn", temperature.toString())),
            )
            val resp = wscApi.startClimate(headers, request)
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Log.d(tag, "Successfully started climate. CIG Request ID: ${body.responseBody.cigServiceRequestId}")
                Result.success(body.responseBody.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed to start climate. Code: ${resp.code()}, Error: $errorBody")
                Result.failure(Exception("Start climate failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during startClimate", e)
            Result.failure(e)
        }
    }

    override suspend fun stopClimate(vehicle: Vehicle, pin: String): Result<String> {
        val vin = vehicle.vin
        val tag = "VehicleService.StopClimate"
        return try {
            Log.d(tag, "Stopping climate for VIN: $vin")
            val headers = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val request = ClimateRequest(
                device = vin,
                extend = false,
                pin = pin,
                vehicleControl = VehicleControl(AcSetting("autoOff", "")),
            )
            val resp = wscApi.stopClimate(headers, request)
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Log.d(tag, "Successfully stopped climate. CIG Request ID: ${body.responseBody.cigServiceRequestId}")
                Result.success(body.responseBody.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed to stop climate. Code: ${resp.code()}, Error: $errorBody")
                Result.failure(Exception("Stop climate failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during stopClimate", e)
            Result.failure(e)
        }
    }

    override suspend fun startCharging(vehicle: Vehicle, pin: String): Result<String> {
        return Result.failure(Exception("Start charging is not supported on this vehicle"))
    }

    override suspend fun stopCharging(vehicle: Vehicle, pin: String): Result<String> {
        return Result.failure(Exception("Stop charging is not supported on this vehicle"))
    }

    override suspend fun setTargetChargeLevel(vehicle: Vehicle, level: Int): Result<String?> {
        val vin = vehicle.vin
        val tag = "VehicleService.SetChargeTarget"
        return try {
            Log.d(tag, "Setting charge target for VIN: $vin to $level")
            val headers = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.setTargetChargeLevel(headers, TargetChargeLevelRequest(vin, level))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Log.d(tag, "Successfully set charge target. CIG Request ID: ${body.responseBody?.cigServiceRequestId}")
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed to set charge target. Code: ${resp.code()}, Error: $errorBody")
                Result.failure(Exception("Set charge target failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during setTargetChargeLevel", e)
            Result.failure(e)
        }
    }

    override suspend fun requestLightHorn(vehicle: Vehicle, pin: String, action: String): Result<String?> {
        val vin = vehicle.vin
        val tag = "VehicleService.LightHorn"
        return try {
            Log.d(tag, "Requesting $action for VIN: $vin")
            val headers = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestLightHorn(action, headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Log.d(tag, "Successfully requested $action. CIG Request ID: ${body.responseBody?.cigServiceRequestId}")
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed Light/Horn request. Code: ${resp.code()}, Error: $errorBody")
                Result.failure(Exception("Light/Horn failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during requestLightHorn", e)
            Result.failure(e)
        }
    }

    override suspend fun requestStopLightHorn(vehicle: Vehicle, pin: String): Result<String?> {
        val vin = vehicle.vin
        val tag = "VehicleService.StopLightHorn"
        return try {
            Log.d(tag, "Requesting stop for lights and horn for VIN: $vin")
            val headers = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestStopLightHorn("sop", headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Log.d(tag, "Successfully requested stop for lights and horn. CIG Request ID: ${body.responseBody?.cigServiceRequestId}")
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed Stop Light/Horn request. Code: ${resp.code()}, Error: $errorBody")
                Result.failure(Exception("Stop Light/Horn failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during requestStopLightHorn", e)
            Result.failure(e)
        }
    }

    override suspend fun requestDoorLock(vehicle: Vehicle, pin: String, action: String): Result<String?> {
        val vin = vehicle.vin
        val tag = "VehicleService.DoorLock"
        return try {
            Log.d(tag, "Requesting $action for VIN: $vin")
            val headers = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestDoorLock(action, headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Log.d(tag, "Successfully requested $action. CIG Request ID: ${body.responseBody?.cigServiceRequestId}")
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed Door lock request. Code: ${resp.code()}, Error: $errorBody")
                Result.failure(Exception("Door lock failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during requestDoorLock", e)
            Result.failure(e)
        }
    }

    override suspend fun getClimateStatus(vehicle: Vehicle): Result<JsonObject> {
        val vin = vehicle.vin
        val tag = "VehicleService.GetClimateStatus"
        return try {
            Log.d(tag, "Getting climate status for VIN: $vin")
            val headers = getHeaders(vehicle, siteId = "1d216af12884813987e6b7f75a005a1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.getClimateStatus(vin, headers)
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                Log.d(tag, "Successfully got climate status")

                var finalBody = body
                val status = body["climateStatus"]?.jsonPrimitive?.content ?: "OFF"
                val startTimeStr = body["climateStartTime"]?.jsonPrimitive?.content

                if (status.uppercase() == "ON" && !startTimeStr.isNullOrEmpty()) {
                    try {
                        val sf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                        val date = sf.parse(startTimeStr)
                        if (date != null) {
                            val diffMs = System.currentTimeMillis() - date.time
                            if (diffMs > 30 * 60 * 1000) {
                                Log.w(tag, "Climate status is ON but startTime ($startTimeStr) is stale (> 30 min). Overriding to OFF.")
                                finalBody = buildJsonObject {
                                    body.forEach { (key, value) ->
                                        if (key == "climateStatus") {
                                            put(key, JsonPrimitive("OFF"))
                                        } else {
                                            put(key, value)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing climateStartTime: $startTimeStr", e)
                    }
                }

                Result.success(finalBody)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed to get climate status. Code: ${resp.code()}, Error: $errorBody")
                Result.failure(Exception("Failed to get climate status: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during getClimateStatus", e)
            Result.failure(e)
        }
    }

    override suspend fun requestVehicleLocation(vehicle: Vehicle, pin: String): Result<String?> {
        val vin = vehicle.vin
        val tag = "VehicleService.VehicleLocation"
        return try {
            Log.d(tag, "Requesting vehicle location for VIN: $vin")
            val headers = getHeaders(vehicle, siteId = "18d216af12884813987e6b7f75a005a1", messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestVehicleLocation("cfl", headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Log.d(tag, "Successfully requested vehicle location. CIG Request ID: ${body.responseBody?.cigServiceRequestId}")
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e(tag, "Failed vehicle location request. Code: ${resp.code()}, Error: $errorBody")
                if (errorBody != null) {
                    try {
                        val error = json.decodeFromString<ErrorResponse>(errorBody)
                        if (error.responseBody.errorCode == "0001-01-2026") {
                            return Result.failure(Exception(error.responseBody.errorMessage))
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Could not parse error body", e)
                    }
                }
                Result.failure(Exception("Vehicle location failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during requestVehicleLocation", e)
            Result.failure(e)
        }
    }
}
