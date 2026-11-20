"""Run actual Android route ownership against simulated Android APIs and real TCP streams."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]
JDK = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Zulu/zulu-17"))
SOURCES = {
    "android/content/Context.java": r"""
package android.content;
public final class Context {
    private final android.net.ConnectivityManager manager;
    public Context(android.net.ConnectivityManager manager) { this.manager = manager; }
    public Context getApplicationContext() { return this; }
    public <T> T getSystemService(Class<T> type) { return type.cast(manager); }
}
""",
    "go/Seq.java": r"""
package go;
public final class Seq { public static void setContext(android.content.Context context) {} }
""",
    "android/net/NetworkCapabilities.java": r"""
package android.net;
public final class NetworkCapabilities {
    public static final int NET_CAPABILITY_INTERNET=1, NET_CAPABILITY_VALIDATED=2, NET_CAPABILITY_NOT_METERED=4;
    public static final int TRANSPORT_WIFI=1, TRANSPORT_CELLULAR=2, TRANSPORT_VPN=4;
    public final int transports, capabilities;
    public NetworkCapabilities(int transports, int capabilities) { this.transports=transports; this.capabilities=capabilities; }
    public boolean hasCapability(int value) { return (capabilities & value) != 0; }
    public boolean hasTransport(int value) { return (transports & value) != 0; }
}
""",
    "android/net/Network.java": r"""
package android.net;
public final class Network {
    public final int id;
    public final java.util.List<Integer> bound = new java.util.concurrent.CopyOnWriteArrayList<>();
    public Network(int id) { this.id=id; }
    public boolean equals(Object value) { return value instanceof Network network && network.id==id; }
    public int hashCode() { return id; }
    public void bindSocket(java.io.FileDescriptor descriptor) throws java.io.IOException {
        int fd=android.os.ParcelFileDescriptor.original(descriptor);
        if (android.os.ParcelFileDescriptor.socket(fd).isClosed()) throw new java.io.IOException("Original socket already closed");
        bound.add(fd);
    }
}
""",
    "android/net/LinkProperties.java": r"""
package android.net;
public final class LinkProperties {
    private final String iface;
    public LinkProperties(String iface) { this.iface=iface; }
    public String getInterfaceName() { return iface; }
    public java.util.List<java.net.InetAddress> getDnsServers() { return java.util.List.of(java.net.InetAddress.getLoopbackAddress()); }
    public java.util.List<RouteInfo> getRoutes() { return java.util.List.of(); }
}
""",
    "android/net/RouteInfo.java": r"""
package android.net;
public final class RouteInfo {
    public boolean isDefaultRoute() { return false; }
    public boolean hasGateway() { return false; }
    public java.net.InetAddress getGateway() { return java.net.InetAddress.getLoopbackAddress(); }
}
""",
    "android/net/ConnectivityManager.java": r"""
package android.net;
public final class ConnectivityManager {
    public static class NetworkCallback {
        public void onAvailable(Network network) {}
        public void onLost(Network network) {}
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {}
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {}
    }
    public volatile Network active;
    public volatile NetworkCapabilities capabilities;
    public volatile LinkProperties properties;
    public volatile boolean metered;
    public volatile NetworkCallback callback;
    public void registerDefaultNetworkCallback(NetworkCallback value) { callback=value; }
    public void unregisterNetworkCallback(NetworkCallback value) {
        if (callback != value) throw new IllegalArgumentException("Callback not registered");
        callback=null;
    }
    public Network getActiveNetwork() { return active; }
    public NetworkCapabilities getNetworkCapabilities(Network network) { return capabilities; }
    public LinkProperties getLinkProperties(Network network) { return properties; }
    public boolean isActiveNetworkMetered() { return metered; }
}
""",
    "android/os/ParcelFileDescriptor.java": r"""
package android.os;
public final class ParcelFileDescriptor implements AutoCloseable {
    private static final java.util.Map<Integer,java.net.Socket> originals=new java.util.HashMap<>();
    private static final java.util.Map<java.io.FileDescriptor,Integer> duplicates=new java.util.IdentityHashMap<>();
    private static int next=10;
    public static int duplicateCloses;
    private final java.io.FileDescriptor descriptor=new java.io.FileDescriptor();
    public static synchronized int register(java.net.Socket socket) { int fd=next++; originals.put(fd,socket); return fd; }
    public static synchronized void unregister(int fd) { originals.remove(fd); }
    public static synchronized java.net.Socket socket(int fd) throws java.io.IOException {
        java.net.Socket socket=originals.get(fd);
        if (socket==null) throw new java.io.IOException("Unknown socket");
        return socket;
    }
    public static synchronized int original(java.io.FileDescriptor descriptor) throws java.io.IOException {
        Integer fd=duplicates.get(descriptor);
        if(fd==null) throw new java.io.IOException("Unknown duplicate");
        return fd;
    }
    private ParcelFileDescriptor(int fd) { duplicates.put(descriptor,fd); }
    public static synchronized ParcelFileDescriptor fromFd(int fd) throws java.io.IOException {
        socket(fd); return new ParcelFileDescriptor(fd);
    }
    public java.io.FileDescriptor getFileDescriptor() { return descriptor; }
    public void close() { synchronized(ParcelFileDescriptor.class) { duplicates.remove(descriptor); duplicateCloses++; } }
}
""",
    "local/phonepc/bridge/mobile/SocketBinder.java": r"""
package local.phonepc.bridge.mobile;
public interface SocketBinder { void bindSocket(long fd) throws Exception; }
""",
    "local/phonepc/bridge/mobile/Client.java": r"""
package local.phonepc.bridge.mobile;
public final class Client {
    public volatile SocketBinder binder;
    public volatile boolean enabled;
    public volatile String snapshot="", iface="", dns="";
    public volatile int starts, disables, enables;
    private final java.util.List<java.net.Socket> sockets=new java.util.ArrayList<>();
    public void start() { starts++; throw new AssertionError("Network callback started a node"); }
    public void setSocketBinder(SocketBinder value) { binder=value; }
    public void setNetworkState(String value, String iface, String gateway) { snapshot=value; this.iface=iface; }
    public void setNetworkDNS(String value) { dns=value; }
    public synchronized void setNetworkEnabled(boolean value) throws java.io.IOException {
        if (!value) {
            enabled=false; disables++;
            for(java.net.Socket socket:sockets) socket.close();
        } else { enabled=true; enables++; }
        notifyAll();
    }
    public java.net.Socket connect(java.net.SocketAddress address) throws Exception {
        if(!enabled) throw new java.io.IOException("Bridge disabled");
        java.net.Socket socket=new java.net.Socket();
        int fd=android.os.ParcelFileDescriptor.register(socket);
        try {
            binder.bindSocket(fd);
            socket.connect(address,2000);
            synchronized(this) { sockets.add(socket); }
            return socket;
        } catch(Exception error) { socket.close(); throw error; }
        finally { android.os.ParcelFileDescriptor.unregister(fd); }
    }
    public void probeBinder() throws Exception {
        try(java.net.Socket socket=new java.net.Socket()) {
            int fd=android.os.ParcelFileDescriptor.register(socket);
            try { binder.bindSocket(fd); }
            finally { android.os.ParcelFileDescriptor.unregister(fd); }
        }
    }
}
""",
    "org/json/JSONArray.java": r"""
package org.json;
public final class JSONArray {
    private final java.util.List<Object> values=new java.util.ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public String toString() { return "["+String.join(",",values.stream().map(JSONObject::encode).toList())+"]"; }
}
""",
    "org/json/JSONObject.java": r"""
package org.json;
public final class JSONObject {
    private final java.util.Map<String,Object> values=new java.util.LinkedHashMap<>();
    public JSONObject put(String key,Object value) { values.put(key,value); return this; }
    static String encode(Object value) {
        if(value instanceof String text) return "\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\"";
        return String.valueOf(value);
    }
    public String toString() {
        return "{"+String.join(",",values.entrySet().stream().map(e->encode(e.getKey())+":"+encode(e.getValue())).toList())+"}";
    }
}
""",
}


class NetworkPolicyCheck(unittest.TestCase):
    def test_actual_callbacks_own_background_socket_network(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="network-policy-", dir=work) as temporary:
            root = Path(temporary)
            sources = []
            for relative, source in SOURCES.items():
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source, encoding="utf-8")
                sources.append(str(target))
            for source in [PROJECT / "bridge-core/android/BridgeNetwork.java",
                           PROJECT / "test-suite/android/NetworkPolicyCheck.java"]:
                target = root / source.name
                shutil.copyfile(source, target)
                sources.append(str(target))
            result = subprocess.run([str(JDK / "bin/javac.exe"), "-d", str(root), *sources],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(root), "NetworkPolicyCheck"],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("Actual network callbacks closed live sockets", result.stdout)


if __name__ == "__main__":
    unittest.main()
