package com.tailapp.ble

import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.ble.protocol.Crc32
import com.tailapp.ble.protocol.OtaCommands
import com.tailapp.ble.protocol.OtaDataFrame
import com.tailapp.ble.protocol.OtaStatusParser
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemInfoParser
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.OtaTransferState
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulated tail's OTA path, driven the way the app drives a real one: BEGIN
 * on FF06, image bytes on FF0E, FINALIZE on FF06. The point is that the simulator
 * cannot pretend to accept an image it never verified — BEGIN checks length,
 * slot and version; the data path honours the offset echo; and FINALIZE refuses a
 * transfer that never completed or whose bytes do not sum to the armed CRC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualTailTransportOtaTest {

    /** Running version the simulator reports, mirrored in its FF06 OTA block. */
    private val running = FirmwareVersion(1, 0, 0)

    private fun begin(image: ByteArray, version: FirmwareVersion, crc: Int = Crc32.compute(image)) =
        OtaCommands.beginUpdate(image.size, crc, version)

    /** Streams the whole [image] in FF0E-sized chunks at the current MTU. */
    private fun stream(transport: VirtualTailTransport, image: ByteArray) {
        val maxPayload = OtaDataFrame.maxPayload(transport.negotiatedMtu.value)
        var offset = 0
        while (offset < image.size) {
            val len = minOf(maxPayload, image.size - offset)
            transport.writeWithoutResponse(
                CharacteristicUuids.OTA_DATA,
                OtaDataFrame.build(offset, image, offset, len)
            )
            offset += len
        }
    }

    private suspend fun otaStatus(transport: VirtualTailTransport) =
        OtaStatusParser.parse(transport.readCharacteristic(CharacteristicUuids.OTA_DATA)!!)!!

    private suspend fun otaBlock(transport: VirtualTailTransport) =
        SystemInfoParser.parse(transport.readCharacteristic(CharacteristicUuids.SYSTEM_CONFIG)!!)!!.ota!!

    @Test
    fun `a good image is armed, streamed and installed`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }
        val acks = collectResults(transport)

        val image = FirmwarePayloads.firmwareImage(version = "2.0.0", sizeBytes = 4000)
        assertTrue(transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, begin(image, FirmwareVersion(2, 0, 0))))
        stream(transport, image)
        transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, OtaCommands.finalizeUpdate())

        val status = otaStatus(transport)
        assertEquals(OtaTransferState.READY, status.state)
        assertEquals(image.size, status.accepted)
        // Every FF06 OTA command is acknowledged OK, and the new image now sits in
        // the other slot ready to run at the next reset.
        assertTrue(acks.all { it == CommandResultCode.OK })
        assertEquals(FirmwareVersion(2, 0, 0), otaBlock(transport).other)
        assertEquals(running, otaBlock(transport).running)
    }

    @Test
    fun `a mismatched CRC is refused at finalize and the transfer is destroyed`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }
        val acks = collectResults(transport)

        val image = FirmwarePayloads.firmwareImage(version = "2.0.0", sizeBytes = 4000)
        // Arm with the wrong CRC: every byte arrives, but they do not sum to it.
        transport.writeCharacteristic(
            CharacteristicUuids.SYSTEM_CONFIG,
            begin(image, FirmwareVersion(2, 0, 0), crc = Crc32.compute(image) + 1)
        )
        stream(transport, image)
        transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, OtaCommands.finalizeUpdate())

        val status = otaStatus(transport)
        assertEquals(OtaTransferState.ERROR, status.state)
        assertEquals(CommandResultCode.BAD_STATE, status.result)
        assertEquals(CommandResultCode.BAD_STATE, acks.last())
        assertNull("a rejected image must not stage the other slot", otaBlock(transport).other)
    }

    @Test
    fun `an image already running is refused at begin`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }

        val image = FirmwarePayloads.firmwareImage(version = "1.0.0", sizeBytes = 4000)
        transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, begin(image, running))

        val status = otaStatus(transport)
        assertEquals(OtaTransferState.ERROR, status.state)
        assertEquals(CommandResultCode.OTA_SAME_VERSION, status.result)
    }

    @Test
    fun `firmware for a different project is refused once its header lands`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }

        val image = FirmwarePayloads.firmwareImage(
            version = "2.0.0", projectName = "SomeoneElse", sizeBytes = 4000
        )
        transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, begin(image, FirmwareVersion(2, 0, 0)))
        // BEGIN passes on the claim; the descriptor check fires when the 288-byte
        // header has arrived, not before.
        assertEquals(OtaTransferState.RECEIVING, otaStatus(transport).state)
        stream(transport, image)

        val status = otaStatus(transport)
        assertEquals(OtaTransferState.ERROR, status.state)
        assertEquals(CommandResultCode.OTA_WRONG_PROJECT, status.result)
    }

    @Test
    fun `an oversized image is refused at begin`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }

        // Nothing is streamed: the declared length alone is over the slot.
        val declaredLength = Protocol.OTA_SLOT_BYTES + 1
        transport.writeCharacteristic(
            CharacteristicUuids.SYSTEM_CONFIG,
            OtaCommands.beginUpdate(declaredLength, 0, FirmwareVersion(2, 0, 0))
        )

        val status = otaStatus(transport)
        assertEquals(OtaTransferState.ERROR, status.state)
        assertEquals(CommandResultCode.OUT_OF_RANGE, status.result)
    }

    @Test
    fun `a file too small to hold a header is refused at begin`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }

        transport.writeCharacteristic(
            CharacteristicUuids.SYSTEM_CONFIG,
            OtaCommands.beginUpdate(Protocol.OTA_IMAGE_HEADER_BYTES - 1, 0, FirmwareVersion(2, 0, 0))
        )

        assertEquals(CommandResultCode.OTA_BAD_IMAGE, otaStatus(transport).result)
    }

    @Test
    fun `a chunk at the wrong offset is discarded and the echo names the resume point`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }

        val image = FirmwarePayloads.firmwareImage(version = "2.0.0", sizeBytes = 4000)
        transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, begin(image, FirmwareVersion(2, 0, 0)))

        // A chunk starting at 100 while the device expects 0: flash is written
        // forward and never rewound, so it is dropped and accepted stays at 0.
        transport.writeWithoutResponse(
            CharacteristicUuids.OTA_DATA,
            OtaDataFrame.build(100, image, 100, 64)
        )
        val status = otaStatus(transport)
        assertEquals(0, status.accepted)
        assertEquals(OtaTransferState.RECEIVING, status.state)
    }

    @Test
    fun `finalize on a short transfer stays armed and resumable`() = runTest {
        val transport = VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }

        val image = FirmwarePayloads.firmwareImage(version = "2.0.0", sizeBytes = 4000)
        transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, begin(image, FirmwareVersion(2, 0, 0)))
        // Only the first chunk: fewer bytes than the update announced.
        transport.writeWithoutResponse(
            CharacteristicUuids.OTA_DATA,
            OtaDataFrame.build(0, image, 0, 480)
        )
        transport.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, OtaCommands.finalizeUpdate())

        val status = otaStatus(transport)
        // Truncated, not corrupt: still RECEIVING, and the echo says carry on from 480.
        assertEquals(OtaTransferState.RECEIVING, status.state)
        assertEquals(480, status.accepted)
    }

    /**
     * Collects the FF09 result codes the simulator emits, in order. Unconfined so
     * the synchronous `tryEmit` in each write is delivered without advancing time.
     */
    private fun kotlinx.coroutines.test.TestScope.collectResults(
        transport: VirtualTailTransport
    ): List<CommandResultCode> {
        val out = mutableListOf<CommandResultCode>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.characteristicUpdate.collect { update ->
                if (update.uuid == CharacteristicUuids.CMD_RESULT) {
                    out += CommandResultCode.fromCode(update.value[2])
                }
            }
        }
        return out
    }
}
