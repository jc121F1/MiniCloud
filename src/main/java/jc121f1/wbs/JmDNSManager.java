package jc121f1.wbs;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

@Slf4j
public class JmDNSManager implements AutoCloseable {

    private final Map<String, JmDNS> jmDNSMap = new HashMap<>();
    private boolean closed;

    @Inject
    public JmDNSManager() {

    }

    public synchronized void startMdns(String hostName, int port) {
        if (closed) {
            throw new IllegalStateException("mDNS manager is closed");
        }
        if (jmDNSMap.get(hostName) != null) {
            log.warn("Failed to start mDNS for {} on port {} as an entry already exists in this manager",
                    hostName, port);
            return;
        }
        JmDNS jmdns = null;
        try {
            jmdns = createResponder(hostName);
            ServiceInfo service = ServiceInfo.create("_http._tcp.local.", hostName, port, "path=/");
            jmdns.registerService(service);
            log.info("mDNS service registered: {} (_http._tcp) on port {}", hostName, port);
            jmDNSMap.put(hostName, jmdns);
        } catch (Exception e) {
            if (jmdns != null) {
                closeResponder(jmdns);
            }
            log.warn("Failed to start mDNS responder", e);
        }
    }

    public synchronized void stopMdns(String hostName) {
        JmDNS jmdns = jmDNSMap.remove(hostName);
        if (jmdns == null) {
            return;
        }
        closeResponder(jmdns);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        var responders = new ArrayList<>(jmDNSMap.values());
        jmDNSMap.clear();
        responders.forEach(this::closeResponder);
    }

    JmDNS createResponder(String hostName) throws Exception {
        InetAddress address = selectAddress();
        JmDNS responder = JmDNS.create(address, hostName);
        log.info("mDNS hostname published: {}.local -> {}", hostName, address.getHostAddress());
        return responder;
    }

    private void closeResponder(JmDNS jmdns) {
        try {
            jmdns.unregisterAllServices();
        } catch (Exception e) {
            log.warn("Failed to unregister mDNS services", e);
        }
        try {
            jmdns.close();
        } catch (Exception e) {
            log.warn("Failed to stop mDNS responder", e);
        }
    }

    // Pick a real LAN interface; InetAddress.getLocalHost() can resolve to loopback, which breaks mDNS multicast.
    public InetAddress selectAddress() throws Exception {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) {
                continue;
            }
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                    return addr;
                }
            }
        }
        InetAddress fallback = InetAddress.getLocalHost();
        if (fallback.isLoopbackAddress()) {
            log.warn("No non-loopback site-local interface found; mDNS bound to {} and multicast may not work", fallback.getHostAddress());
        }
        return fallback;
    }
}
