package com.commafinder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks whether a device's open TCP port actually returns HTTP response headers. */
final class LocalHttpProbe {
    record Result(int statusCode, String detail) {}

    private LocalHttpProbe() {}

    /** Call off the UI thread, after validating the selected device URL. */
    static Result check(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(6_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);

            int statusCode = connection.getResponseCode();
            if (statusCode <= 0) {
                return new Result(0, "HTTP response headers were not received");
            }
            String message = connection.getResponseMessage();
            return new Result(statusCode, "HTTP " + statusCode
                    + (message == null || message.trim().isEmpty() ? "" : " " + concise(message)));
        } catch (IOException | SecurityException error) {
            String message = error.getMessage();
            return new Result(0, error.getClass().getSimpleName()
                    + (message == null || message.trim().isEmpty() ? "" : ": " + concise(message)));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String concise(String message) {
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "…";
    }
}
