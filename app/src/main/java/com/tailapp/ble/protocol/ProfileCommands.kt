package com.tailapp.ble.protocol

object ProfileCommands {
    fun saveProfile(slot: Byte): ByteArray = byteArrayOf(0x01, slot)
    fun loadProfile(slot: Byte): ByteArray = byteArrayOf(0x02, slot)
    fun listProfiles(): ByteArray = byteArrayOf(0x03)
    fun deleteProfile(slot: Byte): ByteArray = byteArrayOf(0x04, slot)
}
