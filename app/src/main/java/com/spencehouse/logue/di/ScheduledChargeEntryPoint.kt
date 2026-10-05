package com.spencehouse.logue.di

import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.VehicleService
import com.spencehouse.logue.service.schedule.ScheduledChargeManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledChargeEntryPoint {
    fun scheduledChargeManager(): ScheduledChargeManager
    fun vehicleService(): VehicleService
    fun authService(): AuthService
}
