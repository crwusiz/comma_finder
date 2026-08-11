package com.commafinder;

final class VersionUtils {

    private VersionUtils() {}

    static boolean isUpdateAvailable(String current, String remote) {
        if (current == null || remote == null) return false;
        try {
            String[] currentParts = current.split("\\.");
            String[] remoteParts = remote.split("\\.");
            int length = Math.max(currentParts.length, remoteParts.length);
            for (int i = 0; i < length; i++) {
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int remotePart = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;
                if (remotePart != currentPart) return remotePart > currentPart;
            }
            return false;
        } catch (NumberFormatException ignored) {
            return !current.equals(remote);
        }
    }
}
