package example.bridge;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import local.phonepc.bridge.BridgeRecovery;
import local.phonepc.bridge.mobile.Client;
import local.phonepc.bridge.mobile.FileClient;
import local.phonepc.bridge.mobile.Operation;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Reserve a phase before scheduling its worker, then pass its token to exactly one SDK call. */
public final class BridgeUsage {
    public static final String PI_HOST = "bridge-pi.tail49b679.ts.net";
    public static final String PI_ID = "nfPvZiSdpi11CNTRL";
    public static final String PC_ID = "nUneuZUkvw11CNTRL";

    private BridgeUsage() {}
    public static Client open(Context context, String stableNodeName) throws Exception {
        return BridgeRecovery.client(context, stableNodeName);
    }
    public static Client open(Context context, String stableNodeName, boolean unmeteredWifiOnly) throws Exception {
        return BridgeRecovery.client(context, stableNodeName, unmeteredWifiOnly);
    }
    public static String newJobId() { return UUID.randomUUID().toString().replace("-", ""); }

    /** Publish this handle together with the owned FutureTask under the app's cancellation lock. */
    public static Operation reservePhase(Client client) throws Exception {
        return client.newOperation();
    }

    /** Evidence for the failed HTTP exchange only; never automatically retry the whole workflow. */
    public static JSONObject preSendTransportFailure(Exception failure) {
        if (failure == null || failure.getMessage() == null) return null;
        try {
            JSONTokener input = new JSONTokener(failure.getMessage());
            Object value = input.nextValue();
            if (!(value instanceof JSONObject) || input.nextClean() != 0) return null;
            JSONObject error = (JSONObject) value;
            Object code = error.opt("code");
            if (error.length() != 5 || !"v1".equals(error.opt("bridgeTransportError"))
                    || !"pre_send".equals(error.opt("phase"))
                    || !Boolean.FALSE.equals(error.opt("requestSent"))
                    || !(error.opt("retryable") instanceof Boolean)
                    || !(code instanceof String) || !((String) code).matches("[a-z][a-z0-9_]{0,63}")) return null;
            return error;
        } catch (org.json.JSONException ignored) {
            return null;
        }
    }

    /** Return HTTP failures intact so the app can retain code, retryable and action. */
    public static JSONObject request(Client client, String operationId, String method, String path, JSONObject body) throws Exception {
        JSONObject wire = new JSONObject(client.callApp(operationId, PI_HOST, PI_ID, method, path, body == null ? "" : body.toString()));
        JSONObject response = wire.getJSONObject("response");
        if (!"phone-pc-bridge-app-v1".equals(response.getString("protocol"))
                || !PI_ID.equals(response.getString("bridgeIdentity")) || !PC_ID.equals(response.getString("pcIdentity")))
            throw new IOException("Uncorrelated bridge response");
        return wire;
    }

    public static JSONObject prepareInput(FileClient files, String operationId, String displayName, File source) throws Exception {
        return new JSONObject(files.prepareFile(operationId, displayName, source.getAbsolutePath()));
    }

    /** Only for an approved UNSENT request that has never been submitted; retain its exact envelope. */
    public static JSONObject restoreUnsentInput(FileClient files, String operationId, JSONObject exactInput, File retainedSource) throws Exception {
        return new JSONObject(files.restorePreparedFile(operationId, exactInput.toString(), retainedSource.getAbsolutePath()));
    }

    /** Persist this exact envelope; submit it only after the app's explicit approval action. */
    public static JSONObject newJob(String originalText, String customInstructions, JSONArray exactInputs, String model, String effort, String serviceTier) throws Exception {
        return new JSONObject().put("requestId", newJobId()).put("createdAt", System.currentTimeMillis())
                .put("title", "App request").put("text", originalText).put("instructions", customInstructions)
                .put("files", exactInputs).put("model", model).put("effort", effort).put("serviceTier", serviceTier);
    }

    /** Resume the original transfer only after a confirmed absent receipt; persist its descriptor first. */
    public static String uploadPersistedInput(FileClient files, String operationId, JSONObject exactInput) throws Exception {
        return files.ensureSent(operationId, exactInput.getString("id"));
    }

    /** After uncertain acknowledgment, read this original ID; never allocate a replacement automatically. */
    public static JSONObject submitPersistedJob(Client client, String operationId, JSONObject immutableEnvelope) throws Exception {
        return request(client, operationId, "POST", "/v2/jobs", immutableEnvelope);
    }

    /** After authenticated job_unavailable: preserve a returned 200 job; persist 410 before local Forget. */
    public static JSONObject retireUnresolvedJob(Client client, String operationId, JSONObject immutableEnvelope) throws Exception {
        String id = immutableEnvelope.getString("requestId");
        if (!id.matches("[0-9a-f]{32}")) throw new IOException("Invalid original request ID");
        return request(client, operationId, "POST", "/v2/jobs/" + id + "/retire", null);
    }

    /** exactOutput comes from this job's completely verified published batch; this phase receives to SDK cache. */
    public static String receiveOutput(FileClient files, String operationId, JSONObject exactOutput) throws Exception {
        return files.receiveMatching(operationId, exactOutput.getString("id"), exactOutput.getString("name"),
                exactOutput.getLong("size"), exactOutput.getString("sha256"));
    }

    /** Check the app flow is still active before starting this separate, cancellable local import. */
    public static void importCachedOutput(FileClient files, String operationId, JSONObject exactOutput, File newAppDestination) throws Exception {
        files.copyReceived(operationId, exactOutput.getString("id"), newAppDestination.getAbsolutePath());
    }

    /** Save the complete app import first. SDK checks actual destination bytes for the whole ordered batch. */
    public static String acknowledgeImportedBatch(Client client, String operationId, String originalJobId, JSONArray imports) throws Exception {
        return client.confirmDelivery(operationId, PI_HOST, PI_ID, originalJobId, imports.toString());
    }

    /** Explicit Wake, Send to Bridge, Retry or settings Wake only, after durably claiming one attempt. Never replay automatically. */
    public static JSONObject wakeFromUserButton(Client client, String operationId) throws Exception {
        return request(client, operationId, "POST", "/v2/connection/wake", null);
    }

    /** Shared power policy/status contains counts and codes, never other apps' job details. */
    public static JSONObject powerStatus(Client client, String operationId) throws Exception {
        return request(client, operationId, "GET", "/v2/connection/power", null);
    }

    /** Explicit user acknowledgment after investigation; current unresolved issues still prevent release. */
    public static JSONObject releasePowerHold(Client client, String operationId, String cycleId) throws Exception {
        if (!cycleId.matches("[0-9a-f]{32}")) throw new IOException("Invalid current power cycle ID");
        return request(client, operationId, "POST", "/v2/connection/power/release", new JSONObject().put("cycleId", cycleId));
    }
}
