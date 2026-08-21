package jc121f1.wbs;

import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

@Slf4j
public class JmDNSManager {

    public static JmDNS startMdns(String hostname, int port) {
        try {
            InetAddress address = selectAddress();
            JmDNS jmdns = JmDNS.create(address, hostname);
            log.info("mDNS hostname published: {}.local -> {}", hostname, address.getHostAddress());
            ServiceInfo service = ServiceInfo.create("_http._tcp.local.", hostname, port, "path=/");
            jmdns.registerService(service);
            log.info("mDNS service registered: {} (_http._tcp) on port {}", hostname, port);
            return jmdns;
        } catch (Exception e) {
            log.warn("Failed to start mDNS responder", e);
            return null;
        }
    }

    public static void stopMdns(JmDNS jmdns) {
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
    public static InetAddress selectAddress() throws Exception {
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
