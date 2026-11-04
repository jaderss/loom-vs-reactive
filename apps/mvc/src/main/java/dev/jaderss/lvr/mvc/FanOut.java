package dev.jaderss.lvr.mvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

final class FanOut {

    private final Executor executor;
    private final BlockingQueue<Future<?>> completed = new LinkedBlockingQueue<>();
    private final List<Future<?>> forked = new ArrayList<>();

    FanOut(Executor executor) {
        this.executor = executor;
    }

    <T> Future<T> fork(Callable<T> task) {
        var future = new FutureTask<>(task) {
            @Override
            protected void done() {
                completed.add(this);
            }
        };
        forked.add(future);
        executor.execute(future);
        return future;
    }

    void join() {
        try {
            for (int i = 0; i < forked.size(); i++) {
                var future = completed.take();
                if (future.state() == Future.State.FAILED) {
                    throw unchecked(future.exceptionNow());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted while waiting for the fan-out");
        } finally {
            forked.forEach(future -> future.cancel(true));
        }
    }

    private static RuntimeException unchecked(Throwable failure) {
        return switch (failure) {
            case RuntimeException e -> e;
            case Error e -> throw e;
            default -> new IllegalStateException(failure);
        };
    }
}
