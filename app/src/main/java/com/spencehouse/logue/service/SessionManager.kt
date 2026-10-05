package com.spencehouse.logue.service

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.spencehouse.logue.service.remote.dto.Vehicle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(@ApplicationContext context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "logue_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var username: String?
        get() = sharedPreferences.getString("honda_username", null)
        set(value) = sharedPreferences.edit { putString("honda_username", value) }

    var password: String?
        get() = sharedPreferences.getString("honda_password", null)
        set(value) = sharedPreferences.edit { putString("honda_password", value) }

    var vin: String?
        get() = sharedPreferences.getString("honda_vin", null)
        set(value) = sharedPreferences.edit { putString("honda_vin", value) }

    var pin: String?
        get() = sharedPreferences.getString("honda_pin", null)
        set(value) = sharedPreferences.edit { putString("honda_pin", value) }

    var useCelsius: Boolean
        get() = sharedPreferences.getBoolean("use_celsius", false)
        set(value) = sharedPreferences.edit { putBoolean("use_celsius", value) }

    var useKilometers: Boolean
        get() = sharedPreferences.getBoolean("use_kilometers", false)
        set(value) = sharedPreferences.edit { putBoolean("use_kilometers", value) }

    var useKpa: Boolean
        get() = sharedPreferences.getBoolean("use_kpa", false)
        set(value) = sharedPreferences.edit { putBoolean("use_kpa", value) }

    var accessToken: String?
        get() = sharedPreferences.getString("access_token", null)
        set(value) = sharedPreferences.edit { putString("access_token", value) }

    var hidasIdent: String?
        get() = sharedPreferences.getString("hidas_ident", null)
        set(value) = sharedPreferences.edit { putString("hidas_ident", value) }

    var clientRegKey: String?
        get() = sharedPreferences.getString("client_reg_key", null)
        set(value) = sharedPreferences.edit { putString("client_reg_key", value) }

    var authPlatform: String?
        get() = sharedPreferences.getString("auth_platform", null)
        set(value) = sharedPreferences.edit { putString("auth_platform", value) }

    var selectedModelYear: String?
        get() = sharedPreferences.getString("selected_model_year", null)
        set(value) = sharedPreferences.edit { putString("selected_model_year", value) }

    var selectedDivisionName: String?
        get() = sharedPreferences.getString("selected_division_name", null)
        set(value) = sharedPreferences.edit { putString("selected_division_name", value) }

    var selectedModelCode: String?
        get() = sharedPreferences.getString("selected_model_code", null)
        set(value) = sharedPreferences.edit { putString("selected_model_code", value) }

    var selectedAliasName: String?
        get() = sharedPreferences.getString("selected_alias_name", null)
        set(value) = sharedPreferences.edit { putString("selected_alias_name", value) }

    var selectedAsset34FrontPath: String?
        get() = sharedPreferences.getString("selected_asset_34_front_path", null)
        set(value) = sharedPreferences.edit { putString("selected_asset_34_front_path", value) }

    var selectedTelematicsPlatform: String?
        get() = sharedPreferences.getString("selected_telematics_platform", null)
        set(value) = sharedPreferences.edit { putString("selected_telematics_platform", value) }

    fun saveSelectedVehicle(vehicle: Vehicle) {
        vin = vehicle.vin
        selectedModelYear = vehicle.modelYear
        selectedDivisionName = vehicle.divisionName
        selectedModelCode = vehicle.modelCode
        selectedAliasName = vehicle.aliasName
        selectedAsset34FrontPath = vehicle.asset34FrontPath
        selectedTelematicsPlatform = vehicle.telematicsPlatform
    }

    fun getSelectedVehicle(vinOverride: String? = null): Vehicle? {
        val targetVin = vinOverride ?: vin ?: return null
        val modelCode = selectedModelCode ?: return null
        return Vehicle(
            vin = targetVin,
            modelYear = selectedModelYear.orEmpty(),
            divisionName = selectedDivisionName.orEmpty(),
            modelCode = modelCode,
            aliasName = selectedAliasName,
            asset34FrontPath = selectedAsset34FrontPath,
            telematicsPlatform = selectedTelematicsPlatform,
        )
    }

    fun logout() {
        sharedPreferences.edit { clear() }
    }

    var cachedBatteryPercentage: Int
        get() = sharedPreferences.getInt("cached_battery_percentage", -1)
        set(value) = sharedPreferences.edit { putInt("cached_battery_percentage", value) }

    var cachedRange: Int
        get() = sharedPreferences.getInt("cached_range", -1)
        set(value) = sharedPreferences.edit { putInt("cached_range", value) }

    var cachedGasRange: Int
        get() = sharedPreferences.getInt("cached_gas_range", -1)
        set(value) = sharedPreferences.edit { putInt("cached_gas_range", value) }

    var cachedChargeStatus: String?
        get() = sharedPreferences.getString("cached_charge_status", null)
        set(value) = sharedPreferences.edit { putString("cached_charge_status", value) }

    var cachedIsPluggedIn: Boolean
        get() = sharedPreferences.getBoolean("cached_is_plugged_in", false)
        set(value) = sharedPreferences.edit { putBoolean("cached_is_plugged_in", value) }

    var cachedClimateStatus: String?
        get() = sharedPreferences.getString("cached_climate_status", null)
        set(value) = sharedPreferences.edit { putString("cached_climate_status", value) }

    var targetChargeLevel: Int
        get() = sharedPreferences.getInt("target_charge_level", 80)
        set(value) = sharedPreferences.edit { putInt("target_charge_level", value) }

    var cachedVoltage: Int
        get() = sharedPreferences.getInt("cached_voltage", -1)
        set(value) = sharedPreferences.edit { putInt("cached_voltage", value) }

    fun getWeeklyChargeScheduleJson(vin: String): String? {
        return sharedPreferences.getString("weekly_charge_schedule_$vin", null)
    }

    fun saveWeeklyChargeScheduleJson(vin: String, json: String) {
        sharedPreferences.edit { putString("weekly_charge_schedule_$vin", json) }
    }
}
