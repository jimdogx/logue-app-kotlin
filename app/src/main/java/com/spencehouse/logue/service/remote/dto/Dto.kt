package com.spencehouse.logue.service.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientRegistrationResponse(
    @SerialName("clientregistrationkey") val clientRegistrationKey: ClientRegistrationKey,
)

@Serializable
data class ClientRegistrationKey(
    @SerialName("client_reg_key") val clientRegKey: String,
)
// endregion

@Serializable
data class TokenResponse(
    @SerialName("request_status") val requestStatus: String,
    @SerialName("token") val token: Token,
    @SerialName("user") val user: User,
)

@Serializable
data class Token(
    @SerialName("access_token") val accessToken: String,
)

@Serializable
data class User(
    @SerialName("hidas_ident") val hidasIdent: String,
)
// endregion

// region My Vehicle
@Serializable
data class VehicleInfoResponse(
    @SerialName("status") val status: String,
    @SerialName("vehicleInfo") val vehicleInfo: List<Vehicle>,
)

@Serializable
data class Vehicle(
    @SerialName("VIN") val vin: String,
    @SerialName("ModelYear") val modelYear: String,
    @SerialName("DivisionName") val divisionName: String,
    @SerialName("ModelCode") val modelCode: String,
    @SerialName("Alias Name") val aliasName: String? = null,
    @SerialName("Asset34FrontPath") val asset34FrontPath: String? = null,
    @SerialName("TelematicsPlatform") val telematicsPlatform: String? = null,
) {
    /**
     * True for GM OnStar / Ultium-based EVs (Acura ZDX, Honda Prologue)
     * that use the ACURA EV CIG backend.
     */
    val isUltiumEv: Boolean
        get() {
            val combined = "$divisionName $modelCode ${aliasName.orEmpty()}".uppercase()
            return combined.contains("ZDX") || combined.contains("PROLOGUE")
        }

    /**
     * True for all supported EV and PHEV models (including Clarity Plug-In Hybrid).
     */
    val isEvOrPhev: Boolean
        get() {
            val combined = "$divisionName $modelCode ${aliasName.orEmpty()} ${telematicsPlatform.orEmpty()}".uppercase()
            return isUltiumEv || combined.contains("EV") || combined.contains("CLARITY") || combined.contains("PHEV")
        }
}
// endregion

// region CIG Token
@Serializable
data class CigTokenRequest(
    val device: String,
)

@Serializable
data class CigTokenResponse(
    val status: String,
    val responseBody: CigTokenResponseBody,
)

@Serializable
data class CigTokenResponseBody(
    val token: String,
    val tokenSignature: String,
)

//endregion

// region Remote Commands
@Serializable
data class DashboardRequest(
    val device: String,
    val filters: List<String>? = null,
)

@Serializable
data class DashboardLatestRequest(
    val evInfoRequest: EvInfoRequest,
) {
    companion object {
        fun forClarityPhev(useKilometers: Boolean = false, useCelsius: Boolean = false): DashboardLatestRequest =
            DashboardLatestRequest(
                evInfoRequest = EvInfoRequest(
                    reqType = "all_info",
                    setUnit = SetUnit(
                        dist = if (useKilometers) "km" else "mile",
                        temp = if (useCelsius) "C" else "F",
                    ),
                    smartChargeEnrolled = false,
                ),
            )
    }
}

@Serializable
data class EvInfoRequest(
    val reqType: String,
    val setUnit: SetUnit,
    val smartChargeEnrolled: Boolean,
)

@Serializable
data class SetUnit(
    val dist: String,
    val temp: String,
)

@Serializable
data class DashboardResponse(
    val status: String,
    val responseBody: DashboardResponseBody,
)

@Serializable
data class DashboardResponseBody(
    val cigServiceRequestId: String,
)

@Serializable
data class ClimateRequest(
    val device: String,
    val extend: Boolean,
    val pin: String,
    val vehicleControl: VehicleControl,
)

@Serializable
data class VehicleControl(
    val acSetting: AcSetting,
)

@Serializable
data class AcSetting(
    val acDefSetting: String, // "autoOn", "autoOff"
    val acTempVal: String,
)

@Serializable
data class ClimateResponse(
    val status: String,
    val responseBody: ClimateResponseBody,
)

@Serializable
data class ClimateResponseBody(
    val cigServiceRequestId: String,
)

@Serializable
data class RemoteCommandRequest(
    val device: String,
    val pin: String,
)

@Serializable
data class RemoteCommandResponse(
    val status: String, // "IN_PROGRESS", "success"
    val responseBody: RemoteCommandResponseBody? = null,
)

@Serializable
data class RemoteCommandResponseBody(
    val cigServiceRequestId: String,
)

@Serializable
data class TargetChargeLevelRequest(
    val device: String,
    val targetChargeLevel: Int,
)

// endregion
