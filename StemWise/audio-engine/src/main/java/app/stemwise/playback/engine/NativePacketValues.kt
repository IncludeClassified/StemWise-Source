package app.stemwise.playback.engine

/** JNI words must be checked before narrowing; overflow cannot alias a valid enum or size. */
internal fun Long.nativePacketInt(): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Native packet integer overflow" }
    return toInt()
}

internal fun Long.nativePacketBoolean(): Boolean {
    require(this == 0L || this == 1L) { "Native packet Boolean must be zero or one" }
    return this == 1L
}

internal fun Long.nativePacketFloat(): Float {
    // Native uint32 bit-casts and signed JVM bit-casts are both canonical 32-bit representations.
    require(this in Int.MIN_VALUE.toLong()..0xffff_ffffL) { "Native packet float bits overflow" }
    return Float.fromBits(toInt()).also { require(it.isFinite()) { "Native packet float must be finite" } }
}

internal fun Long.nativePacketDouble(): Double = Double.fromBits(this).also {
    require(it.isFinite()) { "Native packet double must be finite" }
}
