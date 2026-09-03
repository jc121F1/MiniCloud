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

@Slf4j
public class JmDNSManager {

    private final Map<String, JmDNS> jmDNSMap = new HashMap<>();

    @Inject
    public JmDNSManager() {

    }

    public void startMdns(String hostName, int port) {
        if (jmDNSMap.get(hostName) != null) {
            log.warn("Failed to start mDNS for {} on port {} as an entry already exists in this manager",
                    hostName, port);
        }
        try {
            InetAddress address = selectAddress();
            JmDNS jmdns = JmDNS.create(address, hostName);
            log.info("mDNS hostname published: {}.local -> {}", hostName, address.getHostAddress());
            ServiceInfo service = ServiceInfo.create("_http._tcp.local.", hostName, port, "path=/");
            jmdns.registerService(service);
            log.info("mDNS service registered: {} (_http._tcp) on port {}", hostName, port);
            jmDNSMap.put(hostName, jmdns);
        } catch (Exception e) {
            log.warn("Failed to start mDNS responder", e);
        }
    }

    public void stopMdns(String hostName) {
        JmDNS jmdns = jmDNSMap.get(hostName);
        if (jmdns == null) {
            return;
        }
        try {
            jmdns.unregisterAllServices();
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
