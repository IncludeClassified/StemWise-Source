package local.phonepc.bridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import local.phonepc.bridge.mobile.Client;
import local.phonepc.bridge.mobile.SocketBinder;
import org.json.JSONArray;
import org.json.JSONObject;

/** Supplies actual Android interface state to the embedded engine. No UI or VPN service. */
public final class BridgeNetwork implements AutoCloseable {
    public interface Errors { void onError(Exception error); }
    private final ConnectivityManager manager;
    private final Client client;
    private final Errors errors;
    private final boolean unmeteredWifiOnly;
    private final SocketBinder binder;
    private final Object route = new Object();
    private Network selected;
    private Network applied;
    private volatile Network rejected;
    private String appliedConfiguration = "";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { changed(); }
        @Override public void onLost(Network network) { forbidden(network); changed(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (!allowed(capabilities)) forbidden(network); else available(network);
            changed();
        }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { changed(); }
    };

    /** Create on a worker thread before Client.start(). Errors is called on our worker. */
    public BridgeNetwork(Context context, Client client, Errors errors) throws Exception {
        this(context, client, errors, false);
    }

    /** Policy is process-owned; true allows only validated, unmetered, non-VPN Wi-Fi. */
    public BridgeNetwork(Context context, Client client, Errors errors, boolean unmeteredWifiOnly) throws Exception {
        if (client == null || errors == null) throw new IllegalArgumentException("Client and error callback required.");
        this.client = client;
        this.errors = errors;
        this.unmeteredWifiOnly = unmeteredWifiOnly;
        Context application = context.getApplicationContext();
        go.Seq.setContext(application);
        manager = application.getSystemService(ConnectivityManager.class);
        if (manager == null) { worker.shutdown(); throw new IllegalStateException("Android networking unavailable."); }
        try {
            client.setNetworkEnabled(false);
            binder = new SocketBinder() {
                @Override public void bindSocket(long fd) throws Exception {
                    synchronized (route) {
                        if (closed || selected == null || !approved(selected)) throw new IOException("bridge_network_unavailable");
                        // Duplicate only for this call; never adopt/close the Go-owned descriptor.
                        try (ParcelFileDescriptor socket = ParcelFileDescriptor.fromFd(Math.toIntExact(fd))) {
                            selected.bindSocket(socket.getFileDescriptor());
                        }
                    }
                }
            };
            client.setSocketBinder(binder);
            manager.registerDefaultNetworkCallback(callback);
            // Missing connectivity must not prevent local cache recovery.
            try { refresh(); }
            catch (Exception error) { deny(); errors.onError(error); }
        } catch (Exception error) {
            try { manager.unregisterNetworkCallback(callback); } catch (IllegalArgumentException unregistered) { /* Registration failed. */ }
            worker.shutdown(); throw error;
        }
    }

    private synchronized void available(Network network) { if (network.equals(rejected)) rejected = null; }

    private synchronized void forbidden(Network network) {
        if (closed) return;
        if (!network.equals(manager.getActiveNetwork()) && !network.equals(applied) && !network.equals(rejected)) return;
        rejected = network;
        try { deny(); } catch (Exception error) { errors.onError(error); }
    }

    private boolean allowed(NetworkCapabilities capabilities) {
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false;
        return !unmeteredWifiOnly || (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }

    private boolean approved(Network network) {
        if (network.equals(rejected) || !network.equals(manager.getActiveNetwork())) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return allowed(capabilities) && (!unmeteredWifiOnly || !manager.isActiveNetworkMetered());
    }

    private synchronized void deny() throws Exception {
        synchronized (route) { selected = null; }
        // Do not hold route while Go closes sockets that can call the binder.
        client.setNetworkEnabled(false);
        applied = null;
        appliedConfiguration = "";
    }

    private void changed() {
        if (closed) return;
        synchronized (this) {
            boolean invalid;
            synchronized (route) { invalid = selected != null && !approved(selected); }
            if (invalid) {
                try { deny(); } catch (Exception error) { errors.onError(error); }
            }
        }
        try {
            worker.execute(() -> {
                if (closed) return;
                try { refresh(); }
                catch (Exception error) {
                    try { deny(); } catch (Exception stopping) { error.addSuppressed(stopping); }
                    if (!closed) errors.onError(error);
                }
            });
        } catch (RejectedExecutionException stopped) { if (!closed) throw stopped; }
    }

    private synchronized void refresh() throws Exception {
        if (closed) return;
        Network active = manager.getActiveNetwork();
        LinkProperties properties = active == null ? null : manager.getLinkProperties(active);
        String iface = properties == null ? null : properties.getInterfaceName();
        if (active == null || !approved(active) || iface == null || iface.isEmpty()) { deny(); return; }
        JSONArray dns = new JSONArray();
        for (java.net.InetAddress address : properties.getDnsServers()) dns.put(address.getHostAddress());
        String configuration = iface + ":" + dns;
        boolean replaced = !active.equals(applied) || !configuration.equals(appliedConfiguration);
        if (replaced) deny();
        JSONArray snapshot = new JSONArray();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) throw new IllegalStateException("Android interface inventory unavailable.");
        for (NetworkInterface network : Collections.list(interfaces)) {
            if (!network.getName().equals(iface) && !network.isLoopback()) continue;
            JSONObject entry = new JSONObject().put("name", network.getName()).put("index", network.getIndex())
                    .put("mtu", network.getMTU()).put("up", network.isUp()).put("loopback", network.isLoopback())
                    .put("pointToPoint", network.isPointToPoint()).put("multicast", network.supportsMulticast());
            JSONArray addresses = new JSONArray();
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                String host = address.getAddress().getHostAddress();
                if (host == null) throw new IllegalStateException("Android interface address unavailable.");
                int scope = host.indexOf('%');
                if (scope >= 0) host = host.substring(0, scope);
                addresses.put(host + "/" + address.getNetworkPrefixLength());
            }
            entry.put("addresses", addresses);
            snapshot.put(entry);
        }
        String gateway = "";
        if (properties != null) {
            for (RouteInfo route : properties.getRoutes()) {
                if (route.isDefaultRoute() && route.hasGateway()) {
                    gateway = route.getGateway().getHostAddress();
                    break;
                }
            }
        }
        client.setNetworkState(snapshot.toString(), iface == null ? "" : iface, gateway == null ? "" : gateway);
        if (replaced) client.setNetworkDNS(dns.toString());
        synchronized (route) {
            if (closed || !approved(active)) return;
            selected = active;
        }
        client.setNetworkEnabled(true);
        applied = active;
        appliedConfiguration = configuration;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        synchronized (route) { selected = null; }
        try { manager.unregisterNetworkCallback(callback); }
        finally {
            worker.shutdownNow();
            try { client.setNetworkEnabled(false); }
            catch (Exception error) { errors.onError(error); }
        }
    }
}
