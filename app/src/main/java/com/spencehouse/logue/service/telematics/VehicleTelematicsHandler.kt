package com.spencehouse.logue.service.telematics

import com.spencehouse.logue.service.DashboardData
import com.spencehouse.logue.service.remote.dto.CigTokenResponseBody
import com.spencehouse.logue.service.remote.dto.Vehicle
import kotlinx.serialization.json.JsonObject

data class DashboardRefreshResult(
    val requestId: String,
    /**
     * Optional raw JSON string containing `{ "responseBody": { ... } }` when the telematics
     * platform returns dashboard data directly over HTTP (or HTTP polling) instead of AWS IoT MQTT.
     */
    val directPayloadJson: String? = null,
)

/**
 * Common interface for vehicle telematics operations.
 * Different vehicle families (GM OnStar Ultium EVs vs. HondaLink Connect PHEVs/EVs)
 * implement this interface so callers can simply pass a [Vehicle] and have the
 * appropriate API endpoints, headers, and OAuth credentials selected automatically.
 */
interface VehicleTelematicsHandler {
    suspend fun getDashboardData(vehicle: Vehicle): Result<DashboardData>
    suspend fun getCigToken(vehicle: Vehicle): Result<CigTokenResponseBody>
    suspend fun requestDashboard(vehicle: Vehicle): Result<DashboardRefreshResult>
    suspend fun startClimate(vehicle: Vehicle, pin: String, temperature: Int): Result<String>
    suspend fun stopClimate(vehicle: Vehicle, pin: String): Result<String>
    suspend fun startCharging(vehicle: Vehicle, pin: String = ""): Result<String>
    suspend fun stopCharging(vehicle: Vehicle, pin: String = ""): Result<String>
    suspend fun setTargetChargeLevel(vehicle: Vehicle, level: Int): Result<String?>
    suspend fun requestLightHorn(vehicle: Vehicle, pin: String, action: String): Result<String?>
    suspend fun requestStopLightHorn(vehicle: Vehicle, pin: String): Result<String?>
    suspend fun requestDoorLock(vehicle: Vehicle, pin: String, action: String): Result<String?>
    suspend fun getClimateStatus(vehicle: Vehicle): Result<JsonObject>
    suspend fun requestVehicleLocation(vehicle: Vehicle, pin: String): Result<String?>
}
