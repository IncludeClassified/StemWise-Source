package app.stemwise.playback

import app.stemwise.playback.engine.AudioEngineAudit

internal fun AudioPlayback.setNativeDetailedCapture(enabled: Boolean): Boolean =
    AudioEngineAudit.setDetailedCapture(adapter, enabled)
