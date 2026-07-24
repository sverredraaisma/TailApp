package com.tailapp.ble.protocol

import com.tailapp.model.FirmwareVersion
import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The application-image header reader (A5-1). Its offsets are a mirror of
 * `OtaManager::validate_header`, and both the pre-flight check and the simulated
 * tail read the same bytes — so a wrong offset here would misread a project name
 * or version and let a doomed image through. These pin the exact fields.
 */
class FirmwareImageTest {

    @Test
    fun `it reads the project name and version out of the descriptor`() {
        val info = FirmwareImage.describe(
            FirmwarePayloads.firmwareImage(version = "3.4.5", projectName = "TailFirmware", sizeBytes = 900)
        )!!
        assertEquals("TailFirmware", info.projectName)
        assertEquals("3.4.5", info.versionText)
        assertEquals(FirmwareVersion(3, 4, 5), info.version)
        assertEquals(900, info.sizeBytes)
    }

    @Test
    fun `an unparsable version string is unknown, not zero`() {
        // The firmware treats an unparsable version as unknown; refusing an image
        // over its version string would be worse than installing it.
        val info = FirmwareImage.describe(
            FirmwarePayloads.firmwareImage(version = "dev-build", projectName = "TailFirmware")
        )!!
        assertNull(info.version)
        assertEquals("dev-build", info.versionText)
    }

    @Test
    fun `a file too small to hold the header is not an image`() {
        assertNull(FirmwareImage.describe(ByteArray(Protocol.OTA_IMAGE_HEADER_BYTES - 1)))
    }

    @Test
    fun `the wrong magic byte is not an image`() {
        assertNull(FirmwareImage.describe(FirmwarePayloads.firmwareImage(magic = 0xAB)))
    }

    @Test
    fun `the wrong app-descriptor magic is not an image`() {
        assertNull(FirmwareImage.describe(FirmwarePayloads.firmwareImage(descriptorMagic = 0xDEADBEEFL)))
    }
}
