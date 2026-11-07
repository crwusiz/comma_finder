package com.commafinder;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LocalHttpProbeTest {
    @Test(timeout = 12_000)
    public void reportsHttpStatusWithoutDownloadingResponseBody() throws Exception {
        // The declared body is deliberately absent: the diagnostic needs only headers.
        try (ResponseServer server = new ResponseServer(
                "HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n")) {
            LocalHttpProbe.Result result = LocalHttpProbe.check(server.url());

            assertEquals(200, result.statusCode());
            assertEquals("HTTP 200 OK", result.detail());
        }
    }

    @Test(timeout = 12_000)
    public void reportsRedirectWithoutConnectingToAnotherOrigin() throws Exception {
        try (ResponseServer otherOrigin = new ResponseServer(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
             ResponseServer device = new ResponseServer(
                     "HTTP/1.1 302 Found\r\nLocation: " + otherOrigin.url()
                             + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")) {
            LocalHttpProbe.Result result = LocalHttpProbe.check(device.url());

            assertEquals(302, result.statusCode());
            assertEquals("HTTP 302 Found", result.detail());
            assertEquals(0, otherOrigin.connections.get());
        }
    }

    @Test(timeout = 12_000)
    public void reportsEmptyResponseAsFailureWithoutAssumingMissingPermission() throws Exception {
        try (ResponseServer server = new ResponseServer(null)) {
            LocalHttpProbe.Result result = LocalHttpProbe.check(server.url());

            assertEquals(0, result.statusCode());
            assertFalse(result.detail().isBlank());
            assertFalse(result.detail().toLowerCase(java.util.Locale.ROOT).contains("permission"));
            assertTrue(server.connections.get() > 0);
        }
    }

    /** Accepts repeated connections so any JDK-internal retry also receives a prompt close. */
    private static final class ResponseServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        private volatile boolean running = true;

        ResponseServer(String response) throws IOException {
            listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(250);
            worker = new Thread(() -> serve(response), "local-http-probe-test");
            worker.setDaemon(true);
            worker.start();
        }

        String url() {
            return "http://127.0.0.1:" + listener.getLocalPort() + "/";
        }

        private void serve(String response) {
            while (running) {
                try (Socket socket = listener.accept()) {
                    connections.incrementAndGet();
                    socket.setSoTimeout(2_000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Consume the request headers before replying or closing.
                    }
                    if (response != null) {
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                } catch (SocketTimeoutException ignored) {
                    // Bound both idle accepts and an incomplete test client request.
                } catch (SocketException error) {
                    if (running) {
                        failure.compareAndSet(null, error);
                    }
                    return;
                } catch (IOException error) {
                    failure.compareAndSet(null, error);
                    return;
                }
            }
        }

        @Override
        public void close() throws Exception {
            running = false;
            listener.close();
            worker.join(3_000);
            assertFalse("Loopback test server did not stop", worker.isAlive());
            assertNull("Loopback test server failed", failure.get());
        }
    }
}
