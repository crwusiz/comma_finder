package com.commafinder;

import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

final class PortScanner {

    enum Status { FOUND, NOT_FOUND, CANCELLED }

    record Result(Status status, String ip) {
        static Result found(String ip) {
            return new Result(Status.FOUND, ip);
        }

        static Result notFound() {
            return new Result(Status.NOT_FOUND, null);
        }

        static Result cancelled() {
            return new Result(Status.CANCELLED, null);
        }
    }

    private final int threadCount;
    private final int timeoutMs;
    private volatile boolean cancelled;
    private volatile ExecutorService executor;

    PortScanner(int threadCount, int timeoutMs) {
        this.threadCount = threadCount;
        this.timeoutMs = timeoutMs;
    }

    Result scan(List<String> subnets, int port, IntConsumer progressListener) {
        if (cancelled) return Result.cancelled();

        ExecutorService scanExecutor = Executors.newFixedThreadPool(threadCount);
        executor = scanExecutor;
        ExecutorCompletionService<String> completions =
                new ExecutorCompletionService<>(scanExecutor);
        int submitted = 0;

        try {
            for (String subnet : subnets) {
                for (int host = NetworkUtils.FIRST_HOST; host <= NetworkUtils.LAST_HOST; host++) {
                    String target = subnet + "." + host;
                    completions.submit(() -> {
                        if (cancelled) return null;
                        return NetworkUtils.isPortOpen(target, port, timeoutMs) ? target : null;
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
                String ip = future.get();
                if (ip != null) return Result.found(ip);
            }
            return cancelled ? Result.cancelled() : Result.notFound();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.cancelled();
        } catch (Exception ignored) {
            return cancelled ? Result.cancelled() : Result.notFound();
        } finally {
            scanExecutor.shutdownNow();
            if (executor == scanExecutor) executor = null;
        }
    }

    void cancel() {
        cancelled = true;
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) currentExecutor.shutdownNow();
    }
}
