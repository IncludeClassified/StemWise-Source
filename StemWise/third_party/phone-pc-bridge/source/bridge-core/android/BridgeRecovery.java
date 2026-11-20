package local.phonepc.bridge;

import android.content.Context;
import android.util.Log;
import java.io.File;
import local.phonepc.bridge.mobile.Client;
import local.phonepc.bridge.mobile.Mobile;

/** Process-owned embedded client and Android network callbacks; the app owns its lifecycle. */
public final class BridgeRecovery {
    private static final Object NODE = new Object();
    private static Client sharedClient;
    private static BridgeNetwork sharedNetwork;
    private static String sharedNodeName;
    private static boolean sharedUnmeteredWifiOnly;

    private BridgeRecovery() {}

    /** Worker-thread call. Construction is network-idle; reuse this client and do not close it individually. */
    public static Client client(Context context, String nodeName) throws Exception {
        return client(context, nodeName, false);
    }

    /** The process-owned network policy cannot be changed by another caller of the cached client. */
    public static Client client(Context context, String nodeName, boolean unmeteredWifiOnly) throws Exception {
        synchronized (NODE) {
            if (sharedClient != null) {
                if (!sharedNodeName.equals(nodeName)) throw new IllegalStateException("One embedded node per app process.");
                if (sharedUnmeteredWifiOnly != unmeteredWifiOnly) throw new IllegalStateException("One embedded network policy per app process.");
                return sharedClient;
            }
            Context app = context.getApplicationContext();
            go.Seq.setContext(app);
            Client candidate = Mobile.newClient(new File(app.getNoBackupFilesDir(), "bridge-node").getAbsolutePath(), nodeName, "");
            BridgeNetwork network = null;
            try {
                network = new BridgeNetwork(app, candidate,
                        error -> Log.w("PhonePcBridge", "Embedded network refresh unavailable", error), unmeteredWifiOnly);
                sharedNodeName = nodeName; sharedUnmeteredWifiOnly = unmeteredWifiOnly;
                sharedNetwork = network; sharedClient = candidate;
                return candidate;
            } catch (Exception error) {
                if (network != null) network.close();
                candidate.close();
                throw error;
            }
        }
    }
}
