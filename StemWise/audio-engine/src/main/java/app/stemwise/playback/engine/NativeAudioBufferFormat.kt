package app.stemwise.playback.engine

/** The fixed 48 kHz push backend must hold at least one complete feeder block. */
internal object NativeAudioBufferFormat {
    const val DEFAULT_CAPACITY_FRAMES = 16_384
    const val MIN_CAPACITY_FRAMES = NativePacketSchema.TRACK_BLOCK_FRAMES
    const val MAX_CAPACITY_FRAMES = 1 shl 20

    fun requireCapacity(capacityFrames: Int) {
        require(capacityFrames in MIN_CAPACITY_FRAMES..MAX_CAPACITY_FRAMES) {
            "Native output capacity must be in $MIN_CAPACITY_FRAMES..$MAX_CAPACITY_FRAMES frames"
        }
    }

}
