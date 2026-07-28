package com.tailapp.ble.protocol

import com.tailapp.model.ParamDescriptorKind
import com.tailapp.model.ParamDescriptorSet
import com.tailapp.model.ParamDescriptorStatus
import com.tailapp.model.ParamUnit
import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FF0D parameter-descriptor read, against payloads built exactly as
 * `ConfigManager::build_param_descriptors` writes them.
 *
 * These records are the only thing that bounds an effect parameter: the
 * firmware's `set_param` does not clamp, so an out-of-range
 * `LCMD_SET_EFFECT_PARAM` is accepted silently. A parser that got the record
 * stride wrong would therefore not fail loudly — it would hand the UI plausible
 * numbers read out of the wrong bytes.
 */
class ParamDescriptorParserTest {

    @Test
    fun `the record is 26 bytes and the header 4, as the firmware sizes them`() {
        assertEquals(26, Protocol.PARAM_DESC_RECORD_SIZE)
        assertEquals(4, Protocol.PARAM_DESC_HEADER_SIZE)
        assertEquals(12, Protocol.PARAM_NAME_MAX)
        // PARAM_DESC_MAX_PAYLOAD has to fit one packet at the negotiated MTU,
        // which is the whole reason FF0D publishes one entity at a time.
        assertEquals(4 + 8 * 26, Protocol.PARAM_DESC_MAX_PAYLOAD)
        assertTrue(Protocol.PARAM_DESC_MAX_PAYLOAD < Protocol.MAX_ATT_MTU - 3)
    }

    @Test
    fun `parses an effect's descriptors field for field`() {
        val payload = FirmwarePayloads.paramDescriptors(
            kind = 0x01,
            entityId = 0x06,
            params = listOf(
                FirmwarePayloads.ParamRecord(
                    paramId = 0, unitCode = 0x06, name = "downbeat",
                    min = 0f, max = 255f, default = 200f
                ),
                FirmwarePayloads.ParamRecord(
                    paramId = 1, unitCode = 0x04, name = "decay",
                    min = 0.05f, max = 2f, default = 0.25f
                )
            )
        )

        val set = requireNotNull(ParamDescriptorParser.parse(payload))
        assertEquals(ParamDescriptorKind.EFFECT, set.kind)
        assertEquals(0x06, set.entityId)
        assertEquals(ParamDescriptorStatus.OK, set.status)
        assertTrue(set.isUsable)
        assertTrue(set.describes(ParamDescriptorKind.EFFECT, 0x06))
        assertEquals(2, set.params.size)

        val downbeat = requireNotNull(set.param(0))
        assertEquals("downbeat", downbeat.name)
        assertEquals(ParamUnit.RGB8, downbeat.unit)
        assertEquals(0f, downbeat.min, 0f)
        assertEquals(255f, downbeat.max, 0f)
        assertEquals(200f, downbeat.default, 0f)

        val decay = requireNotNull(set.param(1))
        assertEquals("decay", decay.name)
        assertEquals(ParamUnit.SECONDS, decay.unit)
        assertEquals(0.05f, decay.min, 1e-6f)
        assertEquals(2f, decay.max, 0f)
        assertEquals(0.25f, decay.default, 0f)
    }

    @Test
    fun `the declared range is the only guard on a value`() {
        // The firmware's set_param does not clamp, so a write outside these
        // bounds is accepted and rendered as whatever it produces. Coercing is
        // this side's job, and these are the numbers to coerce against.
        val set = requireNotNull(
            ParamDescriptorParser.parse(
                FirmwarePayloads.paramDescriptors(
                    params = listOf(
                        FirmwarePayloads.ParamRecord(
                            paramId = 0, name = "downbeat", min = 0f, max = 255f, default = 200f
                        )
                    )
                )
            )
        )
        val descriptor = requireNotNull(set.param(0))
        assertFalse(descriptor.isInRange(300f))
        assertFalse(descriptor.isInRange(-1f))
        assertTrue(descriptor.isInRange(255f))
        assertEquals(255f, descriptor.coerce(300f), 0f)
        assertEquals(0f, descriptor.coerce(-1f), 0f)
    }

    @Test
    fun `a name that fills the field has no NUL to stop at`() {
        // PARAM_NAME_MAX bytes exactly: the firmware truncates rather than
        // rejecting, so there is no terminator and the reader must stop at the
        // field width instead of running into the min float.
        val name = "abcdefghijkl" // 12 bytes
        assertEquals(Protocol.PARAM_NAME_MAX, name.length)
        val set = requireNotNull(
            ParamDescriptorParser.parse(
                FirmwarePayloads.paramDescriptors(
                    params = listOf(FirmwarePayloads.ParamRecord(paramId = 3, name = name, min = 7f))
                )
            )
        )
        val descriptor = requireNotNull(set.param(3))
        assertEquals(name, descriptor.name)
        assertEquals(7f, descriptor.min, 0f)
    }

    @Test
    fun `the boot payload is a bare header saying nothing is selected`() {
        // The device publishes this before any SCMD_SELECT_DESCRIPTORS, so a read
        // never comes back empty and "nothing selected" is a state, not a
        // failure.
        val set = requireNotNull(
            ParamDescriptorParser.parse(
                FirmwarePayloads.paramDescriptors(kind = 0xFF, entityId = 0, status = 0x01)
            )
        )
        assertEquals(ParamDescriptorKind.NONE, set.kind)
        assertEquals(ParamDescriptorStatus.NONE, set.status)
        assertFalse(set.isUsable)
        assertEquals(emptyList<Any>(), set.params)
        assertEquals(ParamDescriptorSet.NONE.kind, set.kind)
    }

    @Test
    fun `an unknown id keeps the selection and reports it as unknown`() {
        // The firmware stores the selection even when the id does not exist, so
        // an app watching only FF0D is told "that one does not exist" rather than
        // re-reading the previous entity's descriptors and believing they belong
        // to the id it just asked for.
        val set = requireNotNull(
            ParamDescriptorParser.parse(
                FirmwarePayloads.paramDescriptors(kind = 0x00, entityId = 0x7E, status = 0x02)
            )
        )
        assertEquals(ParamDescriptorKind.PATTERN, set.kind)
        assertEquals(0x7E, set.entityId)
        assertEquals(ParamDescriptorStatus.UNKNOWN_ID, set.status)
        assertFalse(set.isUsable)
        assertTrue(set.params.isEmpty())
    }

    @Test
    fun `an unknown unit code degrades to a plain number`() {
        // Unit codes are appended, never renumbered, and an unknown one must
        // leave a usable name, range and default rather than dropping the
        // descriptor.
        val set = requireNotNull(
            ParamDescriptorParser.parse(
                FirmwarePayloads.paramDescriptors(
                    params = listOf(
                        FirmwarePayloads.ParamRecord(paramId = 0, unitCode = 0x7A, name = "future")
                    )
                )
            )
        )
        val descriptor = requireNotNull(set.param(0))
        assertEquals(ParamUnit.NONE, descriptor.unit)
        assertEquals("future", descriptor.name)
        assertNull(descriptor.unit.suffix)
    }

    @Test
    fun `a payload claiming more records than it carries is dropped, not half-read`() {
        // A truncated record would read a range out of whatever follows it, and a
        // wrong range is worse than no range where the range is the only guard.
        val full = FirmwarePayloads.paramDescriptors(
            params = listOf(
                FirmwarePayloads.ParamRecord(paramId = 0),
                FirmwarePayloads.ParamRecord(paramId = 1)
            )
        )
        assertEquals(Protocol.PARAM_DESC_HEADER_SIZE + 2 * Protocol.PARAM_DESC_RECORD_SIZE, full.size)
        assertNull(ParamDescriptorParser.parse(full.copyOf(full.size - 1)))
    }

    @Test
    fun `a count past the device's own maximum is refused`() {
        val payload = FirmwarePayloads.paramDescriptors().copyOf(Protocol.PARAM_DESC_HEADER_SIZE)
        payload[3] = (Protocol.PARAM_DESC_MAX_PARAMS + 1).toByte()
        assertNull(ParamDescriptorParser.parse(payload))
    }

    @Test
    fun `a short or unrecognisable payload yields null rather than guessing`() {
        assertNull(ParamDescriptorParser.parse(ByteArray(0)))
        assertNull(ParamDescriptorParser.parse(byteArrayOf(0x01, 0x06, 0x00)))
        // An undefined kind and an undefined status are each refused: both are
        // small closed sets, so an unknown value means this is not the payload we
        // think it is.
        assertNull(ParamDescriptorParser.parse(byteArrayOf(0x7F, 0x06, 0x00, 0x00)))
        assertNull(ParamDescriptorParser.parse(byteArrayOf(0x01, 0x06, 0x7F, 0x00)))
    }

    @Test
    fun `eight records is the most the device can send and still parses`() {
        val params = List(Protocol.PARAM_DESC_MAX_PARAMS) {
            FirmwarePayloads.ParamRecord(paramId = it, name = "p$it", min = it.toFloat())
        }
        val payload = FirmwarePayloads.paramDescriptors(params = params)
        assertEquals(Protocol.PARAM_DESC_MAX_PAYLOAD, payload.size)

        val set = requireNotNull(ParamDescriptorParser.parse(payload))
        assertEquals(Protocol.PARAM_DESC_MAX_PARAMS, set.params.size)
        set.params.forEachIndexed { index, descriptor ->
            assertEquals(index, descriptor.paramId)
            assertEquals("p$index", descriptor.name)
            assertEquals(index.toFloat(), descriptor.min, 0f)
        }
    }
}
