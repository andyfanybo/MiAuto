package com.miauto.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public final class HotspotNetworkInfo {
    public final String address;
    public final String mac;
    private HotspotNetworkInfo(String address, String mac) { this.address = address; this.mac = mac; }
    public static Set<String> addresses() {
        Set<String> result = new HashSet<>();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp()) continue;
                for (InetAddress ip : Collections.list(iface.getInetAddresses())) {
                    if (ip instanceof Inet4Address) result.add(ip.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return result;
    }
    static boolean candidate(String iface, String ip, Set<String> before) {
        return iface != null && iface.matches("(?i)(ap|wlan|wifi|swlan|softap|br)[0-9_-].*") &&
                ip != null && !ip.equals("0.0.0.0") && !ip.equals("255.255.255.255") && !ip.startsWith("127.") && !ip.startsWith("169.254.") && (!before.contains(ip) || iface.matches("(?i)(ap|softap)[0-9_-].*"));
    }
    public static HotspotNetworkInfo find(Set<String> before) {
        try {
            List<NetworkInterface> interfaces = new ArrayList<>(Collections.list(NetworkInterface.getNetworkInterfaces()));
            interfaces.sort(Comparator.comparingInt(iface ->
                    iface.getName().startsWith("ap") || iface.getName().startsWith("softap") ? 0 : 1));
            for (NetworkInterface iface : interfaces) {
                if (!iface.isUp()) continue;
                for (InetAddress ip : Collections.list(iface.getInetAddresses())) {
                    if (!(ip instanceof Inet4Address) || !candidate(iface.getName(), ip.getHostAddress(), before)) continue;
                    String mac = "";
                    try {
                        byte[] bytes = iface.getHardwareAddress();
                        if (bytes != null && bytes.length == 6) {
                            StringBuilder out = new StringBuilder();
                            for (byte b : bytes) { if (out.length() > 0) out.append(':'); out.append(String.format("%02x", b & 255)); }
                            mac = out.toString();
                        }
                    } catch (Exception ignored) { }
                    return new HotspotNetworkInfo(ip.getHostAddress(), mac);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
