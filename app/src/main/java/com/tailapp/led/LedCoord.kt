package com.tailapp.led

/**
 * Normalised position of one LED, mirroring the firmware's `LedCoord`
 * (`main/led/led_effect.h`): both axes span `0..1`, x across a ring and y along
 * the tail.
 */
data class LedCoord(val x: Float, val y: Float)
