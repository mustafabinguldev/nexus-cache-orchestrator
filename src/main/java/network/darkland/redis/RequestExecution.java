package network.darkland.redis;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class RequestExecution {
    static final ThreadLocal<RequestExecution> CURRENT = new ThreadLocal<>();
    final String deliveryId;
    final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tasks = new AtomicInteger(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CompletableFuture<Void> completed = new CompletableFuture<>();

    RequestExecution(String deliveryId) { this.deliveryId = deliveryId; }

    Runnable track(Runnable task) {
        tasks.incrementAndGet();
        return () -> {
            RequestExecution previous = CURRENT.get();
            CURRENT.set(this);
            try { task.run(); }
            catch (Throwable error) { fail(error); }
            finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                finish();
            }
        };
    }

    void fail(Throwable error) { failure.compareAndSet(null, error); }

    void finish() {
        if (tasks.decrementAndGet() == 0) {
            Throwable error = failure.get();
            if (error == null) completed.complete(null);
            else completed.completeExceptionally(error);
        }
    }

    void await() throws InterruptedException, java.util.concurrent.ExecutionException {
        completed.get();
    }
}
