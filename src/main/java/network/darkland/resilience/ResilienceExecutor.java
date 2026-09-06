package network.darkland.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ResilienceExecutor {

    private static final Logger LOGGER = Logger.getLogger(ResilienceExecutor.class.getName());

    private ResilienceExecutor() {
    }

    public static <T> CompletableFuture<T> decorateAsync(
            CircuitBreaker circuitBreaker,
            Retry retry,
            ScheduledExecutorService scheduler,
            Supplier<CompletionStage<T>> stageSupplier
    ) {
        Supplier<CompletionStage<T>> withBreaker =
                CircuitBreaker.decorateCompletionStage(circuitBreaker, stageSupplier);

        Supplier<CompletionStage<T>> withRetry =
                Retry.decorateCompletionStage(retry, scheduler, withBreaker);

        return withRetry.get().toCompletableFuture();
    }
    public static <T> T decorateSync(
            CircuitBreaker circuitBreaker,
            Retry retry,
            String label,
            Supplier<T> supplier,
            Supplier<T> fallback
    ) {
        Supplier<T> withBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        Supplier<T> withRetry = Retry.decorateSupplier(retry, withBreaker);

        try {
            return withRetry.get();
        } catch (CallNotPermittedException e) {
            LOGGER.warning("[Resilience/" + circuitBreaker.getName() + "] " + label
                    + " — circuit OPEN, fallback kullanıldı.");
            return fallback.get();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Resilience/" + circuitBreaker.getName() + "] " + label
                    + " — retry tükendi, fallback kullanıldı: " + e.getMessage(), e);
            return fallback.get();
        }
    }

    public static void decorateSyncVoid(
            CircuitBreaker circuitBreaker,
            Retry retry,
            String label,
            Runnable runnable
    ) {
        decorateSync(circuitBreaker, retry, label, () -> {
            runnable.run();
            return null;
        }, () -> null);
    }
}
