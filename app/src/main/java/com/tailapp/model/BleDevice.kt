package com.tailapp.model

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int = 0
)
