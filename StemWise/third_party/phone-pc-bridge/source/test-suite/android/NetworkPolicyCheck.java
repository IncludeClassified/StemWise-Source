import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import local.phonepc.bridge.BridgeNetwork;
import local.phonepc.bridge.mobile.Client;

public final class NetworkPolicyCheck {
    private static final int INTERNET=NetworkCapabilities.NET_CAPABILITY_INTERNET;
    private static final int VALIDATED=NetworkCapabilities.NET_CAPABILITY_VALIDATED;
    private static final int UNMETERED=NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
    private static final int WIFI=NetworkCapabilities.TRANSPORT_WIFI;
    private static final int CELLULAR=NetworkCapabilities.TRANSPORT_CELLULAR;
    private static final int VPN=NetworkCapabilities.TRANSPORT_VPN;
    private static final NetworkCapabilities APPROVED=new NetworkCapabilities(WIFI,INTERNET|VALIDATED|UNMETERED);
    private static String iface;

    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static ConnectivityManager manager(Network network,NetworkCapabilities capabilities,boolean metered) {
        ConnectivityManager manager=new ConnectivityManager();
        manager.active=network; manager.capabilities=capabilities; manager.metered=metered;
        manager.properties=new LinkProperties(iface);
        return manager;
    }
    private static BridgeNetwork network(ConnectivityManager manager,Client client,boolean wifiOnly) throws Exception {
        return new BridgeNetwork(new Context(manager),client,error->{throw new AssertionError("Unexpected network refresh error",error);},wifiOnly);
    }
    private static void drain(BridgeNetwork bridge) throws Exception {
        java.lang.reflect.Field field=BridgeNetwork.class.getDeclaredField("worker");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field.get(bridge);
        worker.submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
    }
    private static void denied(Client client) throws Exception {
        try { client.probeBinder(); throw new AssertionError("Forbidden route bound a new socket"); }
        catch(IOException expected) {}
    }
    private static Socket[] connected(Client client,ServerSocket listener) throws Exception {
        Socket source=client.connect(listener.getLocalSocketAddress());
        Socket peer=listener.accept(); peer.setSoTimeout(2000);
        source.getOutputStream().write(73);
        check(peer.getInputStream().read()==73,"Binder closed the original Go-owned descriptor");
        return new Socket[]{source,peer};
    }
    private static void closed(Socket[] sockets) throws Exception {
        check(sockets[0].isClosed(),"Policy callback did not close its autonomous client socket");
        check(sockets[1].getInputStream().read()==-1,"Remote TCP peer did not observe physical stream closure");
        sockets[1].close();
    }
    private static void initialDenied() throws Exception {
        NetworkCapabilities[] forbidden={
            new NetworkCapabilities(CELLULAR,INTERNET|VALIDATED|UNMETERED),
            new NetworkCapabilities(WIFI,INTERNET|VALIDATED),
            new NetworkCapabilities(WIFI|VPN,INTERNET|VALIDATED|UNMETERED),
            new NetworkCapabilities(WIFI,INTERNET|UNMETERED),
            new NetworkCapabilities(WIFI|CELLULAR,INTERNET|VALIDATED|UNMETERED),
            new NetworkCapabilities(WIFI,VALIDATED|UNMETERED),
            null
        };
        for(NetworkCapabilities capabilities:forbidden) {
            Client client=new Client();
            try(BridgeNetwork ignored=network(manager(new Network(1),capabilities,false),client,true)) {
                check(!client.enabled && client.starts==0,"Initial forbidden network started/enabled the node");
                denied(client);
            }
        }
        Client client=new Client();
        try(BridgeNetwork ignored=network(manager(new Network(1),APPROVED,true),client,true)) {
            check(!client.enabled,"Metered active network bypassed unmetered capability policy");
            denied(client);
        }
        client=new Client();
        try(BridgeNetwork ignored=network(manager(null,null,false),client,true)) {
            check(!client.enabled&&client.starts==0,"Offline construction started/enabled the node");
            denied(client);
        }
    }
    private static void snapshot(Client client) throws Exception {
        Set<String> allowed=new HashSet<>();
        for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if(network.getName().equals(iface)||network.isLoopback()) allowed.add(network.getName());
        }
        Matcher names=Pattern.compile("\"name\":\"([^\"]+)\"").matcher(client.snapshot);
        Set<String> actual=new HashSet<>();
        while(names.find()) actual.add(names.group(1));
        check(actual.equals(allowed),"Snapshot exposed a non-approved host interface: "+actual+" expected "+allowed);
        check(client.iface.equals(iface),"Default route did not use the approved interface");
        check(client.dns.contains(InetAddress.getLoopbackAddress().getHostAddress()),"Selected network DNS was not forwarded");
    }
    private static void lifecycle() throws Exception {
        Network first=new Network(11),replacement=new Network(22);
        ConnectivityManager manager=manager(first,APPROVED,false);
        Client client=new Client();
        try(ServerSocket listener=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
            BridgeNetwork bridge=network(manager,client,true)) {
            listener.setSoTimeout(2000);
            check(client.enabled&&client.starts==0,"Approved network did not enable idle construction");
            snapshot(client);
            Socket[] sockets=connected(client,listener);
            check(first.bound.size()==1&&ParcelFileDescriptor.duplicateCloses>=1,"Socket was not bound through its selected Network duplicate");
            manager.metered=true;
            manager.callback.onCapabilitiesChanged(first,APPROVED);
            check(!client.enabled,"Metering loss did not synchronously deny existing sockets");
            closed(sockets); denied(client);
            manager.metered=false;
            manager.callback.onCapabilitiesChanged(first,APPROVED);
            drain(bridge);
            check(client.enabled,"Allowed Wi-Fi did not restore after metering change");
            sockets=connected(client,listener);
            // Android's inventory intentionally stays stale; the callback's negative evidence must win.
            manager.callback.onCapabilitiesChanged(first,new NetworkCapabilities(WIFI,INTERNET|UNMETERED));
            check(!client.enabled,"Capability loss was deferred behind a worker");
            int enables=client.enables;
            closed(sockets); denied(client);
            manager.callback.onAvailable(first);
            manager.callback.onLinkPropertiesChanged(first,manager.properties);
            drain(bridge);
            check(!client.enabled&&client.enables==enables,"Stale approved inventory reopened a known-forbidden network");
            denied(client);
            manager.callback.onLost(new Network(99));
            drain(bridge);
            check(!client.enabled&&client.enables==enables,"An unrelated old-network callback erased current negative capability evidence");
            denied(client);

            manager.callback.onCapabilitiesChanged(first,APPROVED);
            drain(bridge);
            check(client.enabled,"Fresh positive capabilities did not restore Wi-Fi");
            sockets=connected(client,listener);
            check(first.bound.size()==3,"Restored Wi-Fi did not retain exact route ownership");
            manager.active=replacement;
            manager.callback.onAvailable(replacement);
            closed(sockets);
            drain(bridge);
            check(client.enabled,"Replacement Wi-Fi was not enabled");
            sockets=connected(client,listener);
            check(replacement.bound.size()==1,"Replaced Wi-Fi retained the old Network handle");
            manager.active=null;
            manager.callback.onLost(replacement);
            check(!client.enabled,"Lost Wi-Fi was not denied synchronously");
            closed(sockets); denied(client);
            drain(bridge);
            check(!client.enabled,"Lost route reopened while no default network exists");
            manager.active=replacement;
            manager.callback.onAvailable(replacement);
            manager.callback.onCapabilitiesChanged(replacement,APPROVED);
            drain(bridge);
            check(client.enabled,"Wi-Fi did not restore after fresh availability/capabilities");
            sockets=connected(client,listener);
            check(client.starts==0,"Restore started/replayed an app operation");
            ConnectivityManager.NetworkCallback stale=manager.callback;
            bridge.close();
            closed(sockets);
            check(manager.callback==null&&!client.enabled,"Close retained a callback or enabled node");
            enables=client.enables;
            stale.onAvailable(first); stale.onCapabilitiesChanged(first,APPROVED);
            stale.onLinkPropertiesChanged(first,manager.properties);
            check(client.enables==enables&&!client.enabled,"Late callback resurrected a closed network owner");
            denied(client);
        }
    }
    private static void defaultPolicy() throws Exception {
        Network cellular=new Network(33);
        ConnectivityManager manager=manager(cellular,new NetworkCapabilities(CELLULAR,INTERNET|VALIDATED),true);
        Client client=new Client();
        try(ServerSocket listener=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
            BridgeNetwork ignored=network(manager,client,false)) {
            listener.setSoTimeout(2000);
            Socket[] sockets=connected(client,listener);
            check(cellular.bound.size()==1,"Default active-network policy bypassed explicit socket binding");
            client.setNetworkEnabled(false);
            closed(sockets);
        }
    }
    public static void main(String[] args) throws Exception {
        for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if(network.isLoopback()) { iface=network.getName(); break; }
        }
        check(iface!=null,"Host has no loopback interface");
        initialDenied(); lifecycle(); defaultPolicy();
        System.out.println("Actual network callbacks closed live sockets, pinned exact handles, refused stale routes and stayed idle through restore.");
    }
}
