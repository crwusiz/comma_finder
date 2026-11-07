package com.commafinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

final class PortScanner {

    enum Status { FOUND, NOT_FOUND, CANCELLED }

    record Result(Status status, List<String> ips) {
        Result {
            ips = Collections.unmodifiableList(new ArrayList<>(ips));
        }

        static Result found(List<String> ips) {
            return new Result(Status.FOUND, ips);
        }

        static Result notFound() {
            return new Result(Status.NOT_FOUND, Collections.emptyList());
        }

        static Result cancelled() {
            return new Result(Status.CANCELLED, Collections.emptyList());
        }
    }

    @FunctionalInterface
    interface PortProbe {
        boolean isOpen(String ip, int port, int timeoutMs) throws Exception;
    }

    private final int threadCount;
    private final int timeoutMs;
    private final PortProbe portProbe;
    private volatile boolean cancelled;
    private volatile ExecutorService executor;

    PortScanner(int threadCount, int timeoutMs) {
        this(threadCount, timeoutMs, NetworkUtils::isPortOpen);
    }

    PortScanner(int threadCount, int timeoutMs, PortProbe portProbe) {
        if (threadCount < 1 || timeoutMs < 1) {
            throw new IllegalArgumentException("Thread count and timeout must be positive");
        }
        this.threadCount = threadCount;
        this.timeoutMs = timeoutMs;
        this.portProbe = portProbe;
    }

    Result scan(List<String> subnets, int port, IntConsumer progressListener) {
        if (cancelled) return Result.cancelled();

        ExecutorService scanExecutor = Executors.newFixedThreadPool(threadCount);
        executor = scanExecutor;
        ExecutorCompletionService<String> completions =
                new ExecutorCompletionService<>(scanExecutor);
        int submitted = 0;
        List<String> matches = new ArrayList<>();

        try {
            // Normalize and de-duplicate interfaces on the same IPv4 subnet.
            Set<String> uniqueSubnets = new LinkedHashSet<>();
            for (String subnet : subnets) {
                String normalizedSubnet = NetworkUtils.subnetOf(subnet + ".1");
                if (normalizedSubnet != null) uniqueSubnets.add(normalizedSubnet);
            }
            for (String subnet : uniqueSubnets) {
                for (int host = NetworkUtils.FIRST_HOST; host <= NetworkUtils.LAST_HOST; host++) {
                    if (cancelled) return Result.cancelled();
                    String target = subnet + "." + host;
                    completions.submit(() -> {
                        if (cancelled) return null;
                        return portProbe.isOpen(target, port, timeoutMs) ? target : null;
                    });
                    submitted++;
                }
            }

            int completed = 0;
            while (completed < submitted && !cancelled) {
                Future<String> future = completions.poll(50, TimeUnit.MILLISECONDS);
                if (future == null) continue;

                completed++;
                progressListener.accept(completed);
                try {
                    String ip = future.get();
                    if (ip != null) matches.add(ip);
                } catch (ExecutionException ignored) {
                    // One failed probe must not hide other devices in the scan.
                }
            }
            if (cancelled) return Result.cancelled();
            matches.sort(Comparator.comparingLong(PortScanner::ipv4Value));
            return matches.isEmpty() ? Result.notFound() : Result.found(matches);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.cancelled();
        } catch (RejectedExecutionException rejected) {
            if (cancelled) return Result.cancelled();
            throw rejected;
        } finally {
            scanExecutor.shutdownNow();
            try {
                scanExecutor.awaitTermination(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (executor == scanExecutor) executor = null;
        }
    }

    private static long ipv4Value(String ip) {
        long value = 0;
        for (String octet : ip.split("\\.")) {
            value = (value << 8) | Integer.parseInt(octet);
        }
        return value;
    }

    void cancel() {
        cancelled = true;
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) currentExecutor.shutdownNow();
    }
}
