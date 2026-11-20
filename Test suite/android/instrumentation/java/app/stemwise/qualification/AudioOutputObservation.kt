package app.stemwise.qualification

import app.stemwise.playback.engine.NativeAudioBackend
import app.stemwise.playback.engine.NativeAudioStreamState
import app.stemwise.playback.engine.NativeEngineDiagnosticsSnapshot

/** Absence of an observation is lawful only before the deferred physical output has ever opened. */
internal fun NativeEngineDiagnosticsSnapshot.outputHasNeverOpened(): Boolean {
    val output = output ?: return false
    return output.physicalOpenAttempted == false &&
        !controllerFatalQuarantined && output.backend == NativeAudioBackend.AUDIO_TRACK &&
        (output.streamState == NativeAudioStreamState.UNINITIALIZED || output.streamState == NativeAudioStreamState.CLOSED) &&
        output.sampleRateHz == 0 &&
        output.channelCount == 0 && output.outputBufferSizeFrames == 0 && output.outputBufferCapacityFrames == 0 &&
        !output.recoveryRequired && output.lastErrorCode == 0 &&
        output.physicalRunSequence == 0L && output.callbackCount == 0L && output.callbackFrames == 0L &&
        output.renderedFrames == 0L
}
