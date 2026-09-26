package com.spencehouse.logue.service.telematics

import android.util.Log
import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.Config
import com.spencehouse.logue.service.DashboardData
import com.spencehouse.logue.service.SessionManager
import com.spencehouse.logue.service.remote.HondaWscApi
import com.spencehouse.logue.service.remote.dto.AcSetting
import com.spencehouse.logue.service.remote.dto.CigTokenRequest
import com.spencehouse.logue.service.remote.dto.CigTokenResponseBody
import com.spencehouse.logue.service.remote.dto.ClimateRequest
import com.spencehouse.logue.service.remote.dto.DashboardLatestRequest
import com.spencehouse.logue.service.remote.dto.DashboardRequest
import com.spencehouse.logue.service.remote.dto.RemoteCommandRequest
import com.spencehouse.logue.service.remote.dto.TargetChargeLevelRequest
import com.spencehouse.logue.service.remote.dto.Vehicle
import com.spencehouse.logue.service.remote.dto.VehicleControl
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Telematics handler for HondaLink Connect vehicles (such as the 2018 Honda Clarity PHEV,
 * TelematicsPlatform `2ZSPHEV`, and other non-Ultium HondaLink vehicles).
 *
 * Uses the `HondaLinkAndroidApp0074` OAuth token and `HONDALINK CONNECT` WSC headers,
 * with fallback across HondaLink Connect's dashboard and charge endpoints.
 */
@Singleton
class HondaLinkTelematicsHandler @Inject constructor(
    private val wscApi: HondaWscApi,
    private val authService: AuthService,
    private val sessionManager: SessionManager,
) : VehicleTelematicsHandler {

    private val tag = "HondaLinkTelematicsHandler"

    private fun isClarityPhev(vehicle: Vehicle): Boolean =
        vehicle.modelCode.uppercase(Locale.US).contains("CLARITY")

    private suspend fun getClarityGtcHeaders(vehicle: Vehicle): Result<Map<String, String>> {
        authService.ensureAuthForVehicle(vehicle).onFailure { return Result.failure(it) }
        val accessToken = sessionManager.accessToken ?: return Result.failure(Exception("No access token"))
        val authDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return Result.success(
            mapOf(
                "X-App-Id" to "com.honda.hondalink.connect",
                "X-App-Version" to "5.4.4",
                "X-Device-Id" to "b7a5e8f0-1c2d-4e3f-9a8b-7c6d5e4f3a2b",
                "X-Vin-Auth-Date" to authDate,
                "X-Vin" to vehicle.vin,
                "BearerToken" to accessToken,
            ),
        )
    }

    private suspend fun sendClarityRemoteCommand(vehicle: Vehicle, requestDataJson: String): Result<String> {
        val headers = getClarityGtcHeaders(vehicle).getOrElse { return Result.failure(it) }
        val resp = wscApi.registerClarityRemoteList(headers, requestDataJson, vehicle.vin)
        val body = resp.body()
        if (resp.isSuccessful && body != null) {
            val resultStatus = body["rmt_data"]?.jsonObject?.get("result_status")?.jsonPrimitive?.content
            Log.d(tag, "Clarity RegisterRemoteList response: $body (result_status=$resultStatus)")
            return if (resultStatus == null || resultStatus == "000" || resultStatus == "001" || resultStatus == "010") {
                Result.success(resultStatus ?: "CLARITY_REMOTE_OK")
            } else {
                Result.failure(Exception("Clarity remote command returned status $resultStatus"))
            }
        }
        val errorBody = resp.errorBody()?.string()
        return Result.failure(Exception("Clarity remote command failed (${resp.code()}): $errorBody"))
    }

    private suspend fun getHondaLinkHeaders(
        vehicle: Vehicle,
        siteIdOverride: String? = null,
        version: String = "1.0",
        messageId: String = UUID.randomUUID().toString(),
    ): Result<Map<String, String>> {
        authService.ensureAuthForVehicle(vehicle).onFailure { return Result.failure(it) }
        val accessToken = sessionManager.accessToken ?: return Result.failure(Exception("No access token"))
        val hidasIdent = sessionManager.hidasIdent ?: return Result.failure(Exception("No HIDAS ident"))
        val effectiveSiteId = siteIdOverride ?: sessionManager.clientRegKey ?: "18d216af12884813987e6b7f75a005a1"
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

        return Result.success(
            Config.HONDALINK_COMMON_HEADERS.toMutableMap().apply {
                put("Authorization", "Bearer $accessToken")
                put("bearerToken", accessToken)
                put("hondaHeaderType.version", version)
                put("hondaHeaderType.siteId", effectiveSiteId)
                put("hondaHeaderType.messageId", messageId)
                put("hondaHeaderType.systemId", "com.honda.hondalink.cv_android")
                put("hondaHeaderType.userId", hidasIdent)
                put("hondaHeaderType.hidasId", hidasIdent)
                put("hondaHeaderType.clientType", "Mobile")
                put("hondaHeaderType.deviceID", "b7a5e8f0-1c2d-4e3f-9a8b-7c6d5e4f3a2b")
                put("hondaHeaderType.sessionID", UUID.randomUUID().toString())
                put("hondaHeaderType.collectedTimestamp", timestamp)
                put("Content-Type", "application/json")
                put("Accept", "application/json")
            },
        )
    }

    override suspend fun getCigToken(vehicle: Vehicle): Result<CigTokenResponseBody> {
        val vin = vehicle.vin
        var attempt = 0
        while (attempt < 3) {
            try {
                Log.d(tag, "Fetching HondaLink CIG Token for VIN: $vin, Attempt: ${attempt + 1}")
                val headers = getHondaLinkHeaders(vehicle, siteIdOverride = "b407a3025b374f668475e97d2e750816").getOrElse {
                    return Result.failure(it)
                }
                val resp = wscApi.getCigToken(headers, CigTokenRequest(vin))
                val body = resp.body()
                if (resp.isSuccessful && (body?.status == "Success" || body?.status == "SUCCESS")) {
                    Log.d(tag, "Successfully acquired HondaLink CIG Token")
                    return Result.success(body.responseBody)
                } else {
                    val errorBody = resp.errorBody()?.string()
                    Log.e(tag, "Failed HondaLink CIG Token request. Code: ${resp.code()}, Error: $errorBody")
                    if (resp.code() == 400 && attempt < 2) {
                        delay(1.seconds)
                    } else {
                        return Result.failure(Exception("Failed to get CIG token: $errorBody"))
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception during HondaLink getCigToken", e)
                if (attempt >= 2) return Result.failure(e)
            }
            attempt++
        }
        return Result.failure(Exception("Failed to get HondaLink CIG token after 3 attempts"))
    }

    override suspend fun getDashboardData(vehicle: Vehicle): Result<DashboardData> {
        val vin = vehicle.vin
        return try {
            Log.d(tag, "Requesting HondaLink dashboard data for VIN: $vin")
            val headers = getHondaLinkHeaders(vehicle).getOrElse {
                return Result.failure(it)
            }

            // 1. Fetch latest dashboard state via POST /REST/NGT/CIG/dbd/latest/{vin} with evInfoRequest
            val latestResp = wscApi.getDashboardLatest(
                vin = vin,
                headers = headers,
                request = DashboardLatestRequest.forClarityPhev(),
            )
            if (latestResp.isSuccessful && latestResp.body() != null) {
                val jsonObj = latestResp.body()!!
                Log.d(tag, "HondaLink getDashboardLatest response: $jsonObj")
                parseDashboardMetrics(jsonObj)?.let { return Result.success(it) }
            } else {
                Log.w(tag, "HondaLink getDashboardLatest failed (${latestResp.code()}): ${latestResp.errorBody()?.string()}")
            }

            // 2. Fallback to requestDashboard which triggers async + polls results
            val refreshResult = requestDashboard(vehicle)
            refreshResult.fold(
                onSuccess = {
                    val bat = sessionManager.cachedBatteryPercentage
                    val rng = sessionManager.cachedRange
                    if (bat >= 0 && rng >= 0) {
                        Result.success(DashboardData(bat, rng))
                    } else {
                        Result.failure(Exception("Dashboard refresh triggered (${it.requestId}), awaiting telemetry update"))
                    }
                },
                onFailure = { Result.failure(it) },
            )
        } catch (e: Exception) {
            Log.e(tag, "Exception in HondaLink getDashboardData", e)
            Result.failure(e)
        }
    }

    private fun parseDashboardMetrics(jsonObject: JsonObject): DashboardData? {
        return try {
            val responseBody = jsonObject["responseBody"]?.jsonObject ?: jsonObject
            val evStatus = responseBody["evStatus"]?.jsonObject ?: responseBody
            val vehicleInfo = evStatus["vehicleInfo"]?.jsonObject
            val fuelLevel = responseBody["fuelLevel"]?.jsonObject

            val battery = vehicleInfo?.get("soc")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: evStatus["soc"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: evStatus["batteryLevel"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: responseBody["batteryStatus"]?.jsonObject?.get("soc")?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: responseBody["hvBattery"]?.jsonObject?.get("soc")?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: fuelLevel?.get("currentLevel")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull?.roundToInt()

            val range = vehicleInfo?.get("evRange")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: evStatus["evRange"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: evStatus["range"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: responseBody["evRange"]?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: responseBody["evDriveRange"]?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull?.roundToInt()
                ?: fuelLevel?.get("driveRange")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull?.roundToInt()

            val plugRaw = vehicleInfo?.get("plugStatus")?.jsonObject?.get("value")?.jsonPrimitive?.content
                ?: evStatus["plugStatus"]?.jsonPrimitive?.content
                ?: evStatus["evPlugin"]?.jsonPrimitive?.content
            val chargeRaw = vehicleInfo?.get("chargeStatus")?.jsonObject?.get("value")?.jsonPrimitive?.content
                ?: evStatus["chargeStatus"]?.jsonPrimitive?.content
                ?: evStatus["chgStatus"]?.jsonPrimitive?.content

            if (plugRaw != null || chargeRaw != null) {
                val isPlugged = plugRaw.equals("plugged", ignoreCase = true) ||
                    plugRaw == "1" ||
                    chargeRaw.equals("charging", ignoreCase = true) ||
                    chargeRaw == "1"
                sessionManager.cachedIsPluggedIn = isPlugged
                sessionManager.cachedChargeStatus = when {
                    !isPlugged -> "Unplugged"
                    chargeRaw.equals("charging", ignoreCase = true) || chargeRaw == "1" -> "Charging"
                    chargeRaw.equals("complete", ignoreCase = true) -> "Complete"
                    else -> "Plugged In"
                }
            }

            val acRaw = vehicleInfo?.get("acStatus")?.jsonObject?.get("value")?.jsonPrimitive?.content
            if (acRaw != null) {
                sessionManager.cachedClimateStatus = when (acRaw.lowercase(Locale.US)) {
                    "1", "on" -> "ON"
                    else -> "OFF"
                }
            }

            if (battery != null && range != null) {
                sessionManager.cachedBatteryPercentage = battery
                sessionManager.cachedRange = range
                DashboardData(battery, range)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse HondaLink dashboard metrics", e)
            null
        }
    }

    override suspend fun requestDashboard(vehicle: Vehicle): Result<DashboardRefreshResult> {
        val vin = vehicle.vin
        return try {
            val headers = getHondaLinkHeaders(vehicle).getOrElse {
                return Result.failure(it)
            }
            var lastError = ""

            // Step 1: Fetch latest dashboard state via POST /REST/NGT/CIG/dbd/latest/{vin} with evInfoRequest
            val latestResp = wscApi.getDashboardLatest(
                vin = vin,
                headers = headers,
                request = DashboardLatestRequest.forClarityPhev(),
            )
            if (latestResp.isSuccessful && latestResp.body() != null) {
                val bodyObj = latestResp.body()!!
                parseDashboardMetrics(bodyObj)
                val bodyStr = bodyObj.toString()
                Log.d(tag, "HondaLink POST dbd/latest response: $bodyStr")
                if (bodyStr.contains("responseBody")) {
                    Log.i(tag, "Successfully fetched HondaLink dashboard payload via dbd/latest for VIN: $vin")
                    return Result.success(
                        DashboardRefreshResult(
                            requestId = "LATEST_CACHED",
                            directPayloadJson = bodyStr,
                        ),
                    )
                }
            } else {
                val err = latestResp.errorBody()?.string() ?: ""
                Log.w(tag, "HondaLink POST dbd/latest status ${latestResp.code()}: $err")
                lastError = "(${latestResp.code()}): $err"
            }

            // Step 2: Fallback to triggering async dashboard refresh if dbd/latest did not return a responseBody
            for (filterSet in Config.HONDALINK_DASHBOARD_FILTER_SETS) {
                val asyncHeaders = getHondaLinkHeaders(vehicle).getOrElse { headers }
                Log.d(tag, "Triggering HondaLink Dashboard Async for VIN: $vin with filters: $filterSet")
                val resp = wscApi.requestDashboard(asyncHeaders, DashboardRequest(vin, filterSet))
                val body = resp.body()
                if (resp.isSuccessful && (body?.status.equals("success", ignoreCase = true) || body?.status.equals("IN_PROGRESS", ignoreCase = true))) {
                    val reqId = body?.responseBody?.cigServiceRequestId ?: "OK"
                    Log.i(tag, "HondaLink Dashboard request accepted. RequestID: $reqId (filters=$filterSet)")

                    val polledJson = if (reqId != "OK") pollCigResult("dbd", reqId, asyncHeaders) else null
                    val latestAfterAsync: String? = if (polledJson == null) {
                        val refreshLatest = wscApi.getDashboardLatest(
                            vin = vin,
                            headers = getHondaLinkHeaders(vehicle).getOrElse { headers },
                            request = DashboardLatestRequest.forClarityPhev(),
                        )
                        if (refreshLatest.isSuccessful && refreshLatest.body() != null) {
                            val obj = refreshLatest.body()!!
                            parseDashboardMetrics(obj)
                            obj.toString().takeIf { it.contains("responseBody") }
                        } else {
                            null
                        }
                    } else {
                        null
                    }

                    return Result.success(
                        DashboardRefreshResult(
                            requestId = reqId,
                            directPayloadJson = polledJson ?: latestAfterAsync,
                        ),
                    )
                } else {
                    val errorBody = resp.errorBody()?.string() ?: body?.status ?: "Unknown Error"
                    Log.w(tag, "HondaLink dbd/async with filters=$filterSet returned ${resp.code()}: $errorBody")
                    lastError = "(${resp.code()}): $errorBody"
                }
            }

            Result.failure(Exception("Dashboard Request Failed $lastError"))
        } catch (e: Exception) {
            Log.e(tag, "Exception in HondaLink requestDashboard", e)
            Result.failure(e)
        }
    }

    private suspend fun pollCigResult(engine: String, requestId: String, headers: Map<String, String>): String? {
        for (attempt in 1..4) {
            delay(2.seconds)
            try {
                val resp = wscApi.getCigCommandResult(engine, requestId, headers)
                if (resp.isSuccessful && resp.body() != null) {
                    val bodyStr = resp.body()!!.toString()
                    Log.d(tag, "Polled $engine/results/$requestId (attempt $attempt): $bodyStr")
                    if (bodyStr.contains("evStatus") || bodyStr.contains("odometer") || bodyStr.contains("fuelLevel")) {
                        return bodyStr
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Poll attempt $attempt for $engine/$requestId failed", e)
            }
        }
        return null
    }

    private suspend fun pollChargeResult(requestId: String, headers: Map<String, String>): String? {
        for (attempt in 1..4) {
            delay(2.seconds)
            try {
                val resp = wscApi.getChargeResult("ChargeAsyncLevel", requestId, headers)
                if (resp.isSuccessful && resp.body() != null) {
                    val bodyStr = resp.body()!!.toString()
                    Log.d(tag, "Polled getChargeResult/$requestId (attempt $attempt): $bodyStr")
                    if (bodyStr.contains("responseBody")) {
                        return bodyStr
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Charge poll attempt $attempt for $requestId failed", e)
            }
        }
        return null
    }

    override suspend fun startClimate(vehicle: Vehicle, pin: String, temperature: Int): Result<String> {
        val vin = vehicle.vin
        return try {
            Log.d(tag, "Starting HondaLink climate for VIN: $vin")
            if (isClarityPhev(vehicle)) {
                val requestData = """{"VIN":"$vin","rmt_request":{"req_type":"start_acon","set_start_acon":{"acon_type":"force"}}}"""
                return sendClarityRemoteCommand(vehicle, requestData)
            }
            val headers = getHondaLinkHeaders(vehicle, messageId = "S-1").getOrElse {
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
                Result.success(body.responseBody.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Start climate failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stopClimate(vehicle: Vehicle, pin: String): Result<String> {
        val vin = vehicle.vin
        return try {
            Log.d(tag, "Stopping HondaLink climate for VIN: $vin")
            if (isClarityPhev(vehicle)) {
                val requestData = """{"VIN":"$vin","rmt_request":{"req_type":"stop_acon"}}"""
                return sendClarityRemoteCommand(vehicle, requestData)
            }
            val headers = getHondaLinkHeaders(vehicle, messageId = "S-1").getOrElse {
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
                Result.success(body.responseBody.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Stop climate failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setTargetChargeLevel(vehicle: Vehicle, level: Int): Result<String?> {
        if (isClarityPhev(vehicle)) {
            return Result.failure(Exception("Not supported on Clarity PHEV"))
        }
        val vin = vehicle.vin
        return try {
            val headers = getHondaLinkHeaders(vehicle, messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.setTargetChargeLevel(headers, TargetChargeLevelRequest(vin, level))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Set charge target failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestLightHorn(vehicle: Vehicle, pin: String, action: String): Result<String?> {
        if (isClarityPhev(vehicle)) {
            return Result.failure(Exception("Not supported on Clarity PHEV"))
        }
        val vin = vehicle.vin
        return try {
            val headers = getHondaLinkHeaders(vehicle, messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestLightHorn(action, headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Light/Horn failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestStopLightHorn(vehicle: Vehicle, pin: String): Result<String?> {
        if (isClarityPhev(vehicle)) {
            return Result.failure(Exception("Not supported on Clarity PHEV"))
        }
        val vin = vehicle.vin
        return try {
            val headers = getHondaLinkHeaders(vehicle, messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestStopLightHorn("sop", headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Stop Light/Horn failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestDoorLock(vehicle: Vehicle, pin: String, action: String): Result<String?> {
        if (isClarityPhev(vehicle)) {
            return Result.failure(Exception("Not supported on Clarity PHEV"))
        }
        val vin = vehicle.vin
        return try {
            val headers = getHondaLinkHeaders(vehicle, messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestDoorLock(action, headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Door lock failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getClimateStatus(vehicle: Vehicle): Result<JsonObject> {
        if (isClarityPhev(vehicle)) {
            val status = sessionManager.cachedClimateStatus?.takeIf { it.isNotEmpty() } ?: "OFF"
            return Result.success(
                buildJsonObject {
                    put("climateStatus", status)
                },
            )
        }
        val vin = vehicle.vin
        return try {
            val headers = getHondaLinkHeaders(vehicle).getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.getClimateStatus(vin, headers)
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                Result.success(body)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Failed to get climate status: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestVehicleLocation(vehicle: Vehicle, pin: String): Result<String?> {
        val vin = vehicle.vin
        return try {
            val latestHeaders = getHondaLinkHeaders(vehicle).getOrElse {
                return Result.failure(it)
            }
            val latestResp = wscApi.getDashboardLatest(
                vin = vin,
                headers = latestHeaders,
                request = DashboardLatestRequest.forClarityPhev(),
            )
            if (latestResp.isSuccessful && latestResp.body()?.toString()?.contains("curPosLat") == true) {
                return Result.success("LOCATION_FROM_DBD")
            }

            val headers = getHondaLinkHeaders(vehicle, messageId = "S-1").getOrElse {
                return Result.failure(it)
            }
            val resp = wscApi.requestVehicleLocation("cfl", headers, RemoteCommandRequest(vin, pin))
            val body = resp.body()
            if (resp.isSuccessful && (body?.status == "success" || body?.status == "IN_PROGRESS")) {
                Result.success(body.responseBody?.cigServiceRequestId)
            } else {
                val errorBody = resp.errorBody()?.string()
                Result.failure(Exception("Vehicle location failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
