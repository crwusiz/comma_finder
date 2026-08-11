package com.commafinder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class NetworkUtils {

    static final int FIRST_HOST = 1;
    static final int LAST_HOST = 254;

    private NetworkUtils() {}

    static LinkedHashMap<String, String> getLocalIpv4Addresses(String wifiSsid) throws SocketException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (NetworkInterface networkInterface
                : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;

            String interfaceName = networkInterface.getName().toLowerCase(Locale.ROOT);
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                String ip = address.getHostAddress();
                if (ip == null || address.isLoopbackAddress() || ip.contains(":")) continue;
                if (isPrivateIpv4(ip)) {
                    result.putIfAbsent(ip, networkLabel(interfaceName, ip, wifiSsid));
                }
            }
        }
        return result;
    }

    static List<String> getScannableSubnets() throws SocketException {
        List<String> result = new ArrayList<>();
        for (var entry : getLocalIpv4Addresses("").entrySet()) {
            if (entry.getValue().startsWith("VPN")) continue;
            String subnet = subnetOf(entry.getKey());
            if (subnet != null && !result.contains(subnet)) result.add(subnet);
        }
        return result;
    }

    static String networkLabel(String interfaceName, String ip, String wifiSsid) {
        String name = interfaceName.toLowerCase(Locale.ROOT);
        if (name.contains("tun") || name.contains("tap") || ip.startsWith("172.18.")) {
            return "VPN";
        }
        if (ip.startsWith("172.20.10.")) return "iPhone 핫스팟";
        if (ip.startsWith("192.168.43.")) return "안드로이드 핫스팟";
        if (name.contains("swlan") || name.contains("ap0")) return "핫스팟";
        if (name.contains("rndis") || name.contains("usb")) return "USB테더링";
        if (name.contains("rmnet") || name.contains("ccmni")
                || name.contains("pdp") || name.contains("cellular")) {
            return "데이터";
        }
        if (name.contains("wlan")) return "Wi-Fi" + wifiSsid;
        if (ip.startsWith("10.")) return "모바일데이터/핫스팟";
        return "기타";
    }

    static String subnetOf(String ip) {
        int[] octets = parseIpv4(ip);
        if (octets == null) return null;
        return octets[0] + "." + octets[1] + "." + octets[2];
    }

    static boolean isPrivateIpv4(String ip) {
        int[] octets = parseIpv4(ip);
        if (octets == null) return false;
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    static boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int[] parseIpv4(String ip) {
        if (ip == null) return null;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return null;

        int[] result = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) return null;
                result[i] = Integer.parseInt(parts[i]);
                if (result[i] < 0 || result[i] > 255) return null;
            }
            return result;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
