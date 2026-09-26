package com.spencehouse.logue.service

import android.util.Log
import com.spencehouse.logue.service.remote.dto.CigTokenResponseBody
import com.spencehouse.logue.service.remote.dto.Vehicle
import com.spencehouse.logue.service.telematics.DashboardRefreshResult
import com.spencehouse.logue.service.telematics.HondaLinkTelematicsHandler
import com.spencehouse.logue.service.telematics.UltiumEvTelematicsHandler
import com.spencehouse.logue.service.telematics.VehicleTelematicsHandler
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

data class DashboardData(
    val batteryPercentage: Int,
    val range: Int,
)

/**
 * High-level facade that accepts a [Vehicle] (or VIN) and delegates each operation
 * to the platform-appropriate [VehicleTelematicsHandler]:
 * - [UltiumEvTelematicsHandler] for GM OnStar Ultium EVs (Acura ZDX, Honda Prologue)
 * - [HondaLinkTelematicsHandler] for HondaLink Connect vehicles (Honda Clarity PHEV, etc.)
 */
@Singleton
class VehicleService @Inject constructor(
    private val authService: AuthService,
    private val ultiumHandler: UltiumEvTelematicsHandler,
    private val hondaLinkHandler: HondaLinkTelematicsHandler,
) {
    private val tag = "VehicleService"

    private fun handlerFor(vehicle: Vehicle): VehicleTelematicsHandler {
        return if (vehicle.isUltiumEv) {
            Log.d(tag, "Routing VIN ${vehicle.vin} (${vehicle.modelCode}) -> UltiumEvTelematicsHandler")
            ultiumHandler
        } else {
            Log.d(tag, "Routing VIN ${vehicle.vin} (${vehicle.modelCode}) -> HondaLinkTelematicsHandler")
            hondaLinkHandler
        }
    }

    fun resolveVehicle(vin: String): Vehicle {
        return authService.getSelectedVehicle(vin) ?: Vehicle(
            vin = vin,
            modelYear = "",
            divisionName = "",
            modelCode = "PROLOGUE", // Default fallback to Ultium if no vehicle metadata exists
        )
    }

    // --- Vehicle-based API (primary abstraction) ---

    suspend fun getDashboardData(vehicle: Vehicle): Result<DashboardData> =
        handlerFor(vehicle).getDashboardData(vehicle)

    suspend fun getCigToken(vehicle: Vehicle): Result<CigTokenResponseBody> =
        handlerFor(vehicle).getCigToken(vehicle)

    suspend fun requestDashboardRefresh(vehicle: Vehicle): Result<DashboardRefreshResult> =
        handlerFor(vehicle).requestDashboard(vehicle)

    suspend fun requestDashboard(vehicle: Vehicle): Result<String> =
        handlerFor(vehicle).requestDashboard(vehicle).map { it.requestId }

    suspend fun startClimate(vehicle: Vehicle, pin: String, temperature: Int): Result<String> =
        handlerFor(vehicle).startClimate(vehicle, pin, temperature)

    suspend fun stopClimate(vehicle: Vehicle, pin: String): Result<String> =
        handlerFor(vehicle).stopClimate(vehicle, pin)

    suspend fun setTargetChargeLevel(vehicle: Vehicle, level: Int): Result<String?> =
        handlerFor(vehicle).setTargetChargeLevel(vehicle, level)

    suspend fun requestLightHorn(vehicle: Vehicle, pin: String, action: String): Result<String?> =
        handlerFor(vehicle).requestLightHorn(vehicle, pin, action)

    suspend fun requestStopLightHorn(vehicle: Vehicle, pin: String): Result<String?> =
        handlerFor(vehicle).requestStopLightHorn(vehicle, pin)

    suspend fun requestDoorLock(vehicle: Vehicle, pin: String, action: String): Result<String?> =
        handlerFor(vehicle).requestDoorLock(vehicle, pin, action)

    suspend fun getClimateStatus(vehicle: Vehicle): Result<JsonObject> =
        handlerFor(vehicle).getClimateStatus(vehicle)

    suspend fun requestVehicleLocation(vehicle: Vehicle, pin: String): Result<String?> =
        handlerFor(vehicle).requestVehicleLocation(vehicle, pin)

    // --- VIN-based convenience overloads (used by widgets, background worker, and Wear OS) ---

    suspend fun getDashboardData(vin: String): Result<DashboardData> =
        getDashboardData(resolveVehicle(vin))

    suspend fun getCigToken(vin: String): Result<CigTokenResponseBody> =
        getCigToken(resolveVehicle(vin))

    suspend fun requestDashboard(vin: String): Result<String> =
        requestDashboard(resolveVehicle(vin))

    suspend fun startClimate(vin: String, pin: String, temperature: Int): Result<String> =
        startClimate(resolveVehicle(vin), pin, temperature)

    suspend fun stopClimate(vin: String, pin: String): Result<String> =
        stopClimate(resolveVehicle(vin), pin)

    suspend fun setTargetChargeLevel(vin: String, level: Int): Result<String?> =
        setTargetChargeLevel(resolveVehicle(vin), level)

    suspend fun requestLightHorn(vin: String, pin: String, action: String): Result<String?> =
        requestLightHorn(resolveVehicle(vin), pin, action)

    suspend fun requestStopLightHorn(vin: String, pin: String): Result<String?> =
        requestStopLightHorn(resolveVehicle(vin), pin)

    suspend fun requestDoorLock(vin: String, pin: String, action: String): Result<String?> =
        requestDoorLock(resolveVehicle(vin), pin, action)

    suspend fun getClimateStatus(vin: String): Result<JsonObject> =
        getClimateStatus(resolveVehicle(vin))

    suspend fun requestVehicleLocation(vin: String, pin: String): Result<String?> =
        requestVehicleLocation(resolveVehicle(vin), pin)
}
