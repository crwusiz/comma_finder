package com.commafinder;

import java.net.URI;
import java.net.URISyntaxException;

/** The single local HTTP origin that may be displayed inside the app. */
final class LocalWebTarget {

    static final int PORT = 7000;

    private final String ip;

    private LocalWebTarget(String ip) {
        this.ip = ip;
    }

    static LocalWebTarget fromIp(String ip) {
        if (!isCanonicalPrivateIpv4(ip)) {
            throw new IllegalArgumentException("A canonical private IPv4 address is required");
        }
        return new LocalWebTarget(ip);
    }

    String ip() {
        return ip;
    }

    String rootUrl() {
        return "http://" + ip + ":" + PORT + "/";
    }

    boolean isSameOrigin(String url) {
        URI uri = parseWebUrl(url);
        return uri != null
                && "http".equalsIgnoreCase(uri.getScheme())
                && ip.equals(uri.getHost())
                && uri.getPort() == PORT
                // Keep browser and URI authority parsing in agreement. In particular,
                // do not accept alternate IP spellings or encoded authority characters.
                && (ip + ":" + PORT).equals(uri.getRawAuthority());
    }

    static boolean isExternalWebUrl(String url) {
        return parseWebUrl(url) != null;
    }

    private static URI parseWebUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.isOpaque()
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) {
                return null;
            }
            return uri;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static boolean isCanonicalPrivateIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;

        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3
                    || (part.length() > 1 && part.charAt(0) == '0')) {
                return false;
            }
            int octet = 0;
            for (int j = 0; j < part.length(); j++) {
                char digit = part.charAt(j);
                if (digit < '0' || digit > '9') return false;
                octet = octet * 10 + digit - '0';
            }
            if (octet > 255) return false;
            octets[i] = octet;
        }

        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }
}
