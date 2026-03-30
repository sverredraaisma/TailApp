package com.tailapp.di

import android.content.Context
import android.content.SharedPreferences
import com.tailapp.audio.FftStreamManager
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.BleScanner
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val bleScanner = BleScanner(context)
    val bleConnectionManager = BleConnectionManager(context)
    val deviceRepository = DeviceRepository(bleConnectionManager, applicationScope)
    val fftStreamManager = FftStreamManager(context, deviceRepository, applicationScope)
    val audioPrefs: SharedPreferences = context.getSharedPreferences("audio_config", Context.MODE_PRIVATE)
}
