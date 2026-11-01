package dev.linjian.peek;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One Binder worker, no backlog and no replacement thread when Binder gets stuck. */
final class BoundedCalls {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "GuardianBinder"); thread.setDaemon(true); return thread;
            });

    <T> T call(Callable<T> action, long timeoutMs) throws Exception {
        Future<T> result = worker.submit(action);
        // A timeout does not cancel or replay a Binder transaction whose result is unknown.
        return result.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean offer(Runnable action) {
        try { worker.execute(action); return true; }
        catch (java.util.concurrent.RejectedExecutionException busy) { return false; }
    }
}
