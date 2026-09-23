package com.seatlock;

import com.seatlock.common.SeatConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Runs N tasks that all start at the same instant, to maximize contention.
 * A task that throws SeatConflictException counts as a clean "lost the race".
 * Any other exception fails the test, which is how deadlocks would show up.
 */
public final class Race {

    private Race() {}

    public static List<Boolean> run(int contenders, IntFunction<Callable<?>> taskFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                Callable<?> task = taskFor.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        task.call();
                        return true;
                    } catch (SeatConflictException lost) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown(); // release everyone at once
            List<Boolean> results = new ArrayList<>(contenders);
            for (Future<Boolean> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    public static long wins(List<Boolean> results) {
        return results.stream().filter(Boolean::booleanValue).count();
    }
}
