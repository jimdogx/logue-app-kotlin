package com.spencehouse.logue.service

import android.util.Log
import com.spencehouse.logue.service.remote.HondaWscApi
import com.spencehouse.logue.service.remote.IdentityApi
import com.spencehouse.logue.service.remote.dto.TokenResponse
import com.spencehouse.logue.service.remote.dto.Vehicle
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthService @Inject constructor(
    private val identityApi: IdentityApi,
    private val wscApi: HondaWscApi,
    val sessionManager: SessionManager,
) {
    private val tag = "AuthService"
    var vehicles: List<Vehicle> = emptyList()
    var selectedVin: String? = sessionManager.vin

    suspend fun login(username: String? = null, password: String? = null, vin: String? = null): Result<Unit> {
        val finalUsername = username ?: sessionManager.username
        val finalPassword = password ?: sessionManager.password

        if (finalUsername.isNullOrEmpty() || finalPassword.isNullOrEmpty()) {
            return Result.failure(Exception("No credentials provided"))
        }

        return try {
            Log.d(tag, "Starting login for $finalUsername")
            authenticateWithClient(
                username = finalUsername,
                password = finalPassword,
                useUltiumClient = true,
            ).getOrElse { return Result.failure(it) }

            // 3. Get Vehicles
            val vehicleHeaders = Config.COMMON_HEADERS.toMutableMap().apply {
                put("Authorization", "Bearer ${sessionManager.accessToken}")
                put("hondaHeaderType.version", "2.0")
                put("hondaHeaderType.siteId", "00e0e97f0fb543208a918fc946dea334")
                put("hondaHeaderType.messageId", UUID.randomUUID().toString())
                put("hondaHeaderType.systemId", "com.honda.dealer.cv_android")
                put("hondaHeaderType.userId", sessionManager.hidasIdent!!)
                put("hondaHeaderType.clientType", "Mobile")
                put(
                    "hondaHeaderType.collectedTimeStamp",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
                )
                put("Content-Type", "application/json")
                put("Accept", "application/json")
            }

            val vehiclesResp = wscApi.getVehicles(vehicleHeaders)
            val vehicleData = vehiclesResp.body() ?: return Result.failure(Exception("Failed to get vehicles: ${vehiclesResp.code()}"))

            if (vehicleData.status != "SUCCESS") {
                return Result.failure(Exception("Get vehicles failed: ${vehicleData.status}"))
            }

            this.vehicles = vehicleData.vehicleInfo
            Log.d(tag, "Fetched ${vehicles.size} vehicles")
            if (vehicles.isEmpty()) {
                return Result.failure(Exception("No vehicles found on this account"))
            }

            val savedVin = sessionManager.vin
            this.selectedVin = vin ?: savedVin ?: vehicles.first().vin
            sessionManager.vin = selectedVin
            Log.d(tag, "Selected VIN: $selectedVin")

            val currentVehicle = getSelectedVehicle()
            if (currentVehicle != null) {
                sessionManager.saveSelectedVehicle(currentVehicle)
                ensureAuthForVehicle(currentVehicle)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Login exception", e)
            Result.failure(e)
        }
    }

    suspend fun ensureAuthForVehicle(vehicle: Vehicle): Result<Unit> {
        val targetPlatform = if (vehicle.isUltiumEv) "ULTIUM" else "HONDALINK"
        val hasValidSession = !sessionManager.accessToken.isNullOrEmpty() &&
            !sessionManager.hidasIdent.isNullOrEmpty() &&
            sessionManager.authPlatform == targetPlatform &&
            (vehicle.isUltiumEv || !sessionManager.clientRegKey.isNullOrEmpty())

        if (hasValidSession) {
            return Result.success(Unit)
        }

        val finalUsername = sessionManager.username ?: return Result.failure(Exception("No username stored"))
        val finalPassword = sessionManager.password ?: return Result.failure(Exception("No password stored"))

        Log.d(tag, "Switching auth token to platform $targetPlatform for VIN ${vehicle.vin} (${vehicle.modelCode})")
        return authenticateWithClient(
            username = finalUsername,
            password = finalPassword,
            useUltiumClient = vehicle.isUltiumEv,
        )
    }

    private suspend fun authenticateWithClient(
        username: String,
        password: String,
        useUltiumClient: Boolean,
    ): Result<Unit> {
        val clientId = if (useUltiumClient) Config.CLIENT_ID else Config.HONDALINK_CLIENT_ID
        val clientSecret = if (useUltiumClient) Config.CLIENT_SECRET else Config.HONDALINK_CLIENT_SECRET
        val description = if (useUltiumClient) "Android_Logue_Client" else "Android"
        val platformName = if (useUltiumClient) "ULTIUM" else "HONDALINK"

        // 1. Register Client
        val regResp = identityApi.registerClient(
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
            )
        )
        val clientRegKey = regResp.body()?.clientRegistrationKey?.clientRegKey
            ?: return Result.failure(Exception("Failed to register client ($platformName): ${regResp.code()}"))
        Log.d(tag, "Client registered successfully ($platformName)")

        // 2. Generate Token with retry
        var attempt = 0
        var tokenResp: Response<TokenResponse>? = null
        while (attempt < 3) {
            Log.d(tag, "Attempting to generate token ($platformName), attempt ${attempt + 1}")
            val tokenFields = if (useUltiumClient) {
                mapOf(
                    "client_reg_key" to clientRegKey,
                    "device_description" to description,
                    "username" to username,
                    "password" to password,
                )
            } else {
                mapOf(
                    "client_reg_key" to clientRegKey,
                    "description" to description,
                    "device_description" to description,
                    "username" to username,
                    "password" to password,
                )
            }
            tokenResp = identityApi.generateToken(tokenFields)
            if (tokenResp.isSuccessful) {
                break
            }
            Log.w(tag, "Token generation failed with code: ${tokenResp.code()}. Retrying in 1 second.")
            delay(1.seconds)
            attempt++
        }

        val tokenData = tokenResp?.body() ?: return Result.failure(Exception("Auth failed ($platformName): ${tokenResp?.code()}"))
        if (tokenData.requestStatus != "success") {
            return Result.failure(Exception("Auth status ($platformName): ${tokenData.requestStatus}"))
        }

        sessionManager.accessToken = tokenData.token.accessToken
        sessionManager.hidasIdent = tokenData.user.hidasIdent
        sessionManager.clientRegKey = clientRegKey
        sessionManager.authPlatform = platformName
        sessionManager.username = username
        sessionManager.password = password
        Log.d(tag, "Token generated and session saved for $platformName")
        return Result.success(Unit)
    }

    fun updateSelectedVin(vin: String) {
        this.selectedVin = vin
        sessionManager.vin = vin
        vehicles.find { it.vin == vin }?.let { sessionManager.saveSelectedVehicle(it) }
        Log.d(tag, "Persistence updated for VIN: $vin")
    }

    fun getSelectedVehicle(vinOverride: String? = null): Vehicle? {
        val targetVin = vinOverride ?: selectedVin ?: sessionManager.vin
        val inMemory = vehicles.find { it.vin == targetVin } ?: vehicles.firstOrNull()
        if (inMemory != null) {
            return inMemory
        }
        return sessionManager.getSelectedVehicle(targetVin)
    }

    fun logout() {
        Log.d(tag, "Logging out")
        sessionManager.logout()
        vehicles = emptyList()
        selectedVin = null
    }

    fun getVehicleName(): String {
        val vehicle = getSelectedVehicle()
        val name = vehicle?.aliasName ?: vehicle?.let { "${it.modelYear} ${it.divisionName} ${it.modelCode}" } ?: "Unknown Vehicle"
        Log.d(tag, "getVehicleName: $name (selectedVin: $selectedVin, vehicleCount: ${vehicles.size})")
        return name
    }

    fun isLoggedIn(): Boolean {
        return (sessionManager.username != null) && (sessionManager.password != null)
    }
}
