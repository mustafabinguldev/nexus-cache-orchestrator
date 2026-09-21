package network.darkland.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RequestExecutionTest {
    @Test
    void nestedBindingsAreRestoredEvenAfterFailure() throws Exception {
        var outer = new RequestExecution("outer");
        var inner = new RequestExecution("inner");
        var failure = new IllegalStateException("failed task");
        assertNull(RequestExecution.current());
        outer.run(() -> {
            assertSame(outer, RequestExecution.current());
            inner.run(() -> {
                assertSame(inner, RequestExecution.current());
                throw failure;
            });
            assertSame(outer, RequestExecution.current());
        });
        outer.await();
        assertSame(failure, assertThrows(ExecutionException.class, inner::await).getCause());
        assertNull(RequestExecution.current());
    }

    @Test
    void completionWaitsForTrackedTasksAndNestedTasks() throws Exception {
        var execution = new RequestExecution("delivery");
        var childStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            execution.run(() -> executor.execute(execution.track(() -> {
                executor.execute(execution.track(() -> {
                    childStarted.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                    assertSame(execution, RequestExecution.current());
                    execution.dirtyKeys.add("saved");
                }));
            })));
            assertTrue(childStarted.await(2, TimeUnit.SECONDS));
            var completion = executor.submit(() -> { execution.await(); return null; });
            try {
                assertThrows(TimeoutException.class, () -> completion.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            completion.get(2, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of("saved"), execution.dirtyKeys);
        } finally { release.countDown(); }
    }

    @Test
    void asyncFailurePreventsSuccessfulCompletion() throws Exception {
        var execution = new RequestExecution("failed");
        var failure = new IllegalArgumentException("write failed");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            execution.run(() -> executor.execute(execution.track(() -> { throw failure; })));
            assertSame(failure, assertThrows(ExecutionException.class, execution::await).getCause());
        }
    }

    @Test
    void concurrentDeliveriesStayIsolated() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deliveries = IntStream.range(0, 100).mapToObj(i -> {
                var execution = new RequestExecution("delivery-" + i);
                execution.run(() -> executor.execute(execution.track(() -> {
                    assertSame(execution, RequestExecution.current());
                    execution.dirtyKeys.add(RequestExecution.current().deliveryId);
                })));
                return execution;
            }).toList();
            for (var execution : deliveries) {
                execution.await();
                assertEquals(java.util.Set.of(execution.deliveryId), execution.dirtyKeys);
            }
        }
        assertNull(RequestExecution.current());
    }

    @Test
    void reusedWorkerDoesNotRetainDeliveryContext() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var execution = new RequestExecution("delivery");
            execution.run(() -> executor.execute(execution.track(() -> assertSame(execution, RequestExecution.current()))));
            execution.await();
            assertNull(executor.submit(RequestExecution::current).get(2, TimeUnit.SECONDS));
        }
    }
}