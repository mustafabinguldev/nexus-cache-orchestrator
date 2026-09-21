package network.darkland.redis;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class RequestExecution {
    private static final ScopedValue<RequestExecution> CURRENT = ScopedValue.newInstance();
    final String deliveryId;
    final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tasks = new AtomicInteger(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CompletableFuture<Void> completed = new CompletableFuture<>();

    RequestExecution(String deliveryId) { this.deliveryId = deliveryId; }

    static RequestExecution current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    // Bind explicitly on each executor task: ordinary executors do not inherit scoped values.
    // The binding is restored automatically, including when a task fails or scopes nest.
    void run(Runnable task) {
        try { ScopedValue.where(CURRENT, this).run(task); }
        catch (Throwable error) { fail(error); }
        finally { finish(); }
    }

    Runnable track(Runnable task) {
        tasks.incrementAndGet();
        return () -> run(task);
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
