"""Exercise the real shared factory; Android and generated native bindings are boundaries."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]
JDK = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Zulu/zulu-17"))
SOURCES = {
    "android/content/Context.java": """package android.content;
public final class Context {
    private final java.io.File storage;
    public Context(java.io.File storage) { this.storage = storage; }
    public Context getApplicationContext() { return this; }
    public java.io.File getNoBackupFilesDir() { return storage; }
}
""",
    "android/util/Log.java": """package android.util;
public final class Log { public static int w(String tag, String text, Throwable error) { return 0; } }
""",
    "go/Seq.java": """package go;
public final class Seq { public static void setContext(android.content.Context context) {} }
""",
    "local/phonepc/bridge/mobile/Client.java": """package local.phonepc.bridge.mobile;
public final class Client {
    public static int starts;
    public void start() { starts++; throw new AssertionError("Factory started the embedded node"); }
    public void close() {}
}
""",
    "local/phonepc/bridge/mobile/Mobile.java": """package local.phonepc.bridge.mobile;
public final class Mobile {
    public static int creations;
    public static Client newClient(String state, String name, String key) {
        if (!new java.io.File(state).isAbsolute() || !key.isEmpty())
            throw new AssertionError("Factory changed private storage or enrollment ownership");
        creations++;
        return new Client();
    }
}
""",
    "local/phonepc/bridge/BridgeNetwork.java": """package local.phonepc.bridge;
public final class BridgeNetwork implements AutoCloseable {
    public interface Errors { void onError(Exception error); }
    public static int creations;
    public static boolean selectedPolicy;
    public BridgeNetwork(android.content.Context context, local.phonepc.bridge.mobile.Client client, Errors errors, boolean wifiOnly) {
        creations++;
        selectedPolicy = wifiOnly;
    }
    public void close() {}
}
""",
    "RecoveryFactoryCheck.java": """import android.content.Context;
import local.phonepc.bridge.BridgeRecovery;
import local.phonepc.bridge.BridgeNetwork;
import local.phonepc.bridge.mobile.Client;
import local.phonepc.bridge.mobile.Mobile;
public final class RecoveryFactoryCheck {
    public static void main(String[] args) throws Exception {
        Context context = new Context(new java.io.File(args[0]));
        boolean wifiOnly = Boolean.parseBoolean(args[1]);
        Client first = BridgeRecovery.client(context, "offline-recovery", wifiOnly);
        if (first != BridgeRecovery.client(context, "offline-recovery", wifiOnly))
            throw new AssertionError("Factory lost the process-owned cached client");
        try {
            BridgeRecovery.client(context, "different-node", wifiOnly);
            throw new AssertionError("Factory created a second process node");
        } catch (IllegalStateException expected) {}
        try {
            BridgeRecovery.client(context, "offline-recovery", !wifiOnly);
            throw new AssertionError("Cached factory bypassed its process-owned network policy");
        } catch (IllegalStateException expected) {}
        if (!wifiOnly && first != BridgeRecovery.client(context, "offline-recovery"))
            throw new AssertionError("Existing two-argument factory lost its default policy");
        if (Client.starts != 0 || Mobile.creations != 1 || BridgeNetwork.creations != 1 || BridgeNetwork.selectedPolicy != wifiOnly)
            throw new AssertionError("Factory started networking or duplicated its owner");
        System.out.println("Shared factory stayed network-idle and retained one process-owned client.");
    }
}
""",
}


class RecoveryFactoryCheck(unittest.TestCase):
    def test_actual_shared_factory_is_network_idle(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="recovery-factory-", dir=work) as temporary:
            root = Path(temporary)
            sources = []
            for relative, source in SOURCES.items():
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source, encoding="utf-8")
                sources.append(str(target))
            factory = root / "local/phonepc/bridge/BridgeRecovery.java"
            shutil.copyfile(PROJECT / "bridge-core/android/BridgeRecovery.java", factory)
            sources.append(str(factory))
            result = subprocess.run([str(JDK / "bin/javac.exe"), "-d", str(root), *sources],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for wifi_only in ["false", "true"]:
                result = subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(root), "RecoveryFactoryCheck", str(root), wifi_only],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("stayed network-idle", result.stdout)


if __name__ == "__main__":
    unittest.main()
