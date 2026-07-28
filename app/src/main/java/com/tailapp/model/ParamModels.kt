package com.tailapp.model

/**
 * Which kind of entity a descriptor set describes (`PARAM_DESC_KIND_*`).
 *
 * [NONE] is the device's "nothing selected yet" sentinel, not an error: FF0D is
 * readable before any `SCMD_SELECT_DESCRIPTORS` has been sent, and the firmware
 * publishes this header at boot so a read never comes back empty.
 */
enum class ParamDescriptorKind(val code: Int) {
    PATTERN(0x00),
    EFFECT(0x01),
    NONE(0xFF);

    companion object {
        fun fromCode(code: Int): ParamDescriptorKind? = entries.find { it.code == code }
    }
}

/** Whether the selected entity had descriptors to publish (`PARAM_DESC_STATUS_*`). */
enum class ParamDescriptorStatus(val code: Int) {
    OK(0x00),

    /** No selection has been made yet. */
    NONE(0x01),

    /**
     * The selected id does not exist on this device. The firmware stores the
     * selection anyway, so an app watching only FF0D is told "that one does not
     * exist" rather than re-reading the previous entity's descriptors and
     * believing they belong to the id it just asked for.
     */
    UNKNOWN_ID(0x02);

    companion object {
        fun fromCode(code: Int): ParamDescriptorStatus? = entries.find { it.code == code }
    }
}

/**
 * What a parameter's numbers *mean* (`PARAM_UNIT_*`) — how to label and format a
 * control for it.
 *
 * Appended, never renumbered, and an unknown code degrades to a plain number,
 * which is what [NONE] means anyway. That is why the parser maps an unrecognised
 * code to [NONE] instead of dropping the descriptor: a firmware unit this build
 * has never heard of still leaves a usable name, range and default.
 */
enum class ParamUnit(val code: Int) {
    /** Dimensionless scalar or gain. */
    NONE(0x00),
    DEGREES(0x01),
    DEGREES_PER_SECOND(0x02),
    HERTZ(0x03),
    SECONDS(0x04),

    /** Normalised 0-1 fraction. */
    RATIO(0x05),

    /** One 0-255 colour channel. */
    RGB8(0x06),
    BPM(0x07),

    /** Integer choice; the descriptor's min/max bound the valid ids. */
    ENUM(0x08),

    /** 0 or 1. */
    BOOL(0x09),

    /** Integer count of things. */
    COUNT(0x0A),

    /** A rate: cycles, events or decay per second. */
    PER_SECOND(0x0B),

    /** Spatial, in strip-lengths. */
    LENGTHS(0x0C);

    /** Suffix to render after a value, or null when the number stands alone. */
    val suffix: String?
        get() = when (this) {
            NONE, RATIO, RGB8, ENUM, BOOL, COUNT -> null
            DEGREES -> "°"
            DEGREES_PER_SECOND -> "°/s"
            HERTZ -> "Hz"
            SECONDS -> "s"
            BPM -> "BPM"
            PER_SECOND -> "/s"
            LENGTHS -> "×"
        }

    /** True when the value is a whole number and a slider should step by 1. */
    val isIntegral: Boolean get() = this == ENUM || this == BOOL || this == COUNT

    companion object {
        /** Unknown codes fold to [NONE] — "plain number" is the documented degradation. */
        fun fromCode(code: Int): ParamUnit = entries.find { it.code == code } ?: NONE
    }
}

/**
 * One parameter's declared schema, from an FF0D record.
 *
 * The device reads these off the pattern or effect object itself, so whatever a
 * firmware feature says about itself is what the app is told and the two cannot
 * disagree. That also means a parameter an older app has never heard of still
 * arrives with a usable name, range and default instead of nothing at all.
 *
 * [min] and [max] are the **only** guard on a value: the firmware's `set_param`
 * does not clamp, so a write outside the declared range is accepted and the
 * effect renders whatever that produces. Sending one is a bug on this side, not
 * something the tail will refuse.
 */
data class ParamDescriptor(
    /** Index into the eight parameter slots FF02/FF04 report for the entity. */
    val paramId: Int,
    val unit: ParamUnit,
    /** Up to `PARAM_NAME_MAX` bytes on the wire, NUL-padded and truncated, not rejected. */
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float
) {
    /** [value] brought inside the declared range — the clamp the firmware does not do. */
    fun coerce(value: Float): Float = value.coerceIn(min, max)

    /** True if [value] is one the device would render as the author intended. */
    fun isInRange(value: Float): Boolean = value in min..max
}

/**
 * The whole FF0D read: which entity is selected, whether it had anything to say,
 * and its parameter schema.
 *
 * One entity at a time by design — with eleven patterns and eighteen effects at
 * up to eight parameters each, the full set is several kilobytes, past both the
 * FF06 buffer and any single MTU. Selecting one keeps every read a fixed,
 * single-packet payload.
 */
data class ParamDescriptorSet(
    val kind: ParamDescriptorKind,
    /** The pattern or effect id these describe. Meaningless when [kind] is `NONE`. */
    val entityId: Int,
    val status: ParamDescriptorStatus,
    val params: List<ParamDescriptor>
) {
    /** True when [params] describes the entity the app asked about. */
    val isUsable: Boolean get() = status == ParamDescriptorStatus.OK

    fun param(paramId: Int): ParamDescriptor? = params.find { it.paramId == paramId }

    /** True when this set is the answer to a select of [kind] and [entityId]. */
    fun describes(kind: ParamDescriptorKind, entityId: Int): Boolean =
        this.kind == kind && this.entityId == entityId

    companion object {
        /** What the device publishes at boot: a header with nothing selected. */
        val NONE = ParamDescriptorSet(
            kind = ParamDescriptorKind.NONE,
            entityId = 0,
            status = ParamDescriptorStatus.NONE,
            params = emptyList()
        )
    }
}
