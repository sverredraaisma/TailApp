package com.tailapp.model

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int
) {
    val isTailController: Boolean
        get() = name == "Tail controller"
}
