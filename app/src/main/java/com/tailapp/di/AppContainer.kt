package com.tailapp.di

import android.content.Context
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.BleScanner

class AppContainer(context: Context) {
    val bleScanner = BleScanner(context)
    val bleConnectionManager = BleConnectionManager(context)
}
