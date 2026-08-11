package com.commafinder;

import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PortScannerTest {

    @Test
    public void findsOpenPort() throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            PortScanner scanner = new PortScanner(8, 100);
            PortScanner.Result result = scanner.scan(
                    List.of("127.0.0"), server.getLocalPort(), ignored -> {});

            assertEquals(PortScanner.Status.FOUND, result.status());
            assertEquals("127.0.0.1", result.ip());
        }
    }

    @Test
    public void respectsCancellationBeforeScan() {
        PortScanner scanner = new PortScanner(1, 100);
        scanner.cancel();

        PortScanner.Result result = scanner.scan(List.of("127.0.0"), 1, ignored -> {});

        assertEquals(PortScanner.Status.CANCELLED, result.status());
    }
}
