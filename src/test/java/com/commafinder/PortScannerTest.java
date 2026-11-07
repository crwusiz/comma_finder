package com.commafinder;

import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortScannerTest {

    @Test(timeout = 15000)
    public void findsOpenPort() throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            PortScanner scanner = new PortScanner(8, 100);
            PortScanner.Result result = scanner.scan(
                    List.of("127.0.0"), server.getLocalPort(), ignored -> {});

            assertEquals(PortScanner.Status.FOUND, result.status());
            assertEquals(List.of("127.0.0.1"), result.ips());
        }
    }

    @Test(timeout = 5000)
    public void waitsForSlowerMatchesAndCompletesEveryHost() {
        CountDownLatch laterProbesCompleted = new CountDownLatch(1);
        AtomicInteger progress = new AtomicInteger();
        PortScanner scanner = new PortScanner(4, 100, (ip, port, timeout) -> {
            if (ip.equals("192.168.1.2")) return true;
            if (ip.equals("192.168.1.10")) {
                return laterProbesCompleted.await(3, TimeUnit.SECONDS);
            }
            return false;
        });

        PortScanner.Result result = scanner.scan(List.of("192.168.1"), 7000, completed -> {
            progress.set(completed);
            if (completed >= 25) laterProbesCompleted.countDown();
        });

        assertEquals(PortScanner.Status.FOUND, result.status());
        assertEquals(List.of("192.168.1.2", "192.168.1.10"), result.ips());
        assertEquals(254, progress.get());
    }

    @Test(timeout = 5000)
    public void sortsIpv4NumericallyAndScansDuplicateSubnetsOnlyOnce() {
        AtomicInteger probes = new AtomicInteger();
        List<Integer> progress = new ArrayList<>();
        PortScanner scanner = new PortScanner(8, 100, (ip, port, timeout) -> {
            probes.incrementAndGet();
            return ip.endsWith(".10") || ip.endsWith(".2");
        });

        PortScanner.Result result = scanner.scan(
                List.of("192.168.10", "192.168.2", "192.168.10"),
                7000, progress::add);

        assertEquals(PortScanner.Status.FOUND, result.status());
        assertEquals(List.of("192.168.2.2", "192.168.2.10",
                "192.168.10.2", "192.168.10.10"), result.ips());
        assertEquals(508, probes.get());
        assertEquals(508, progress.size());
        for (int i = 0; i < progress.size(); i++) {
            assertEquals(i + 1, progress.get(i).intValue());
        }
        assertThrows(UnsupportedOperationException.class, () -> result.ips().add("10.0.0.1"));
    }

    @Test(timeout = 5000)
    public void continuesAfterOneProbeFails() {
        AtomicInteger progress = new AtomicInteger();
        PortScanner scanner = new PortScanner(4, 100, (ip, port, timeout) -> {
            if (ip.endsWith(".1")) throw new IllegalStateException("Probe failed");
            return ip.endsWith(".254");
        });

        PortScanner.Result result = scanner.scan(List.of("192.168.1"), 7000, progress::set);

        assertEquals(PortScanner.Status.FOUND, result.status());
        assertEquals(List.of("192.168.1.254"), result.ips());
        assertEquals(254, progress.get());
    }

    @Test(timeout = 5000)
    public void returnsNotFoundAfterCompletingAllHosts() {
        AtomicInteger progress = new AtomicInteger();
        PortScanner scanner = new PortScanner(4, 100, (ip, port, timeout) -> false);

        PortScanner.Result result = scanner.scan(List.of("192.168.1"), 7000, progress::set);

        assertEquals(PortScanner.Status.NOT_FOUND, result.status());
        assertTrue(result.ips().isEmpty());
        assertEquals(254, progress.get());
    }

    @Test
    public void respectsCancellationBeforeScan() {
        PortScanner scanner = new PortScanner(1, 100);
        scanner.cancel();

        PortScanner.Result result = scanner.scan(List.of("127.0.0"), 1, ignored -> {});

        assertEquals(PortScanner.Status.CANCELLED, result.status());
        assertTrue(result.ips().isEmpty());
    }

    @Test(timeout = 5000)
    public void cancellationDiscardsAlreadyFoundAddresses() {
        PortScanner scanner = new PortScanner(1, 100, (ip, port, timeout) -> true);

        PortScanner.Result result = scanner.scan(
                List.of("192.168.1"), 7000, completed -> scanner.cancel());

        assertEquals(PortScanner.Status.CANCELLED, result.status());
        assertTrue(result.ips().isEmpty());
    }

    @Test(timeout = 5000)
    public void cancelsWhileProbeIsRunning() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        AtomicBoolean probeInterrupted = new AtomicBoolean();
        PortScanner scanner = new PortScanner(1, 100, (ip, port, timeout) -> {
            probeStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                probeInterrupted.set(true);
                throw interrupted;
            }
            return true;
        });
        ExecutorService scanThread = Executors.newSingleThreadExecutor();
        try {
            Future<PortScanner.Result> future = scanThread.submit(
                    () -> scanner.scan(List.of("192.168.1"), 7000, ignored -> {}));
            assertTrue(probeStarted.await(2, TimeUnit.SECONDS));

            scanner.cancel();
            PortScanner.Result result = future.get(2, TimeUnit.SECONDS);

            assertEquals(PortScanner.Status.CANCELLED, result.status());
            assertTrue(result.ips().isEmpty());
            assertTrue(probeInterrupted.get());
        } finally {
            scanner.cancel();
            scanThread.shutdownNow();
            assertTrue(scanThread.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 5000)
    public void preservesScanThreadInterruption() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        AtomicBoolean cancelledResult = new AtomicBoolean();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        PortScanner scanner = new PortScanner(1, 100, (ip, port, timeout) -> {
            probeStarted.countDown();
            new CountDownLatch(1).await();
            return false;
        });
        Thread scanThread = new Thread(() -> {
            PortScanner.Result result = scanner.scan(List.of("192.168.1"), 7000, ignored -> {});
            cancelledResult.set(result.status() == PortScanner.Status.CANCELLED);
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });
        try {
            scanThread.start();
            assertTrue(probeStarted.await(2, TimeUnit.SECONDS));
            scanThread.interrupt();
            scanThread.join(2000);

            assertFalse(scanThread.isAlive());
            assertTrue(cancelledResult.get());
            assertTrue(interruptPreserved.get());
        } finally {
            scanner.cancel();
            scanThread.interrupt();
            scanThread.join(1000);
        }
    }
}
