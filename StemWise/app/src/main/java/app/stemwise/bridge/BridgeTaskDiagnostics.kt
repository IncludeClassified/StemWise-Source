package app.stemwise.bridge

import org.json.JSONArray
import org.json.JSONObject

/** User-requested projection of the existing bounded attempt history, without task input/media. */
internal fun bridgeTaskDiagnostics(record: BridgeSongRecord): String = JSONObject()
    .put("requestId", record.task.id).put("videoId", record.task.videoId)
    .put("libraryId", record.root.libraryId.take(128)).put("rootEpoch", record.root.epoch)
    .put("state", record.task.state.take(32)).put("stage", record.task.stage.take(180))
    .put("message", record.task.message ?: JSONObject.NULL)
    .put("cancelRequested", record.task.cancelRequested).put("imported", record.imported)
    .put("submission", record.submission.name).put("outputVerified", record.outputVerified)
    .put("connection", record.connection?.json() ?: JSONObject.NULL)
    .put("remoteRetired", record.remoteRetired)
    .put("attempts", JSONArray().also { array -> record.attempts.takeLast(8).forEach {
        array.put(it.copy(stage = it.stage.take(128), cause = it.cause?.take(4096)).json())
    } }).toString(2)
