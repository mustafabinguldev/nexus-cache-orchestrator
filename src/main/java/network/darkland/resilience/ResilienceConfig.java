package network.darkland.resilience;

import com.mongodb.MongoException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ResilienceConfig {

    private static final Logger LOGGER = Logger.getLogger(ResilienceConfig.class.getName());

    public static final String MONGO_INSTANCE = "mongo";
    public static final String REDIS_INSTANCE = "redis";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final ScheduledExecutorService retryScheduler;

    private final CircuitBreaker mongoCircuitBreaker;
    private final Retry mongoRetry;

    private final CircuitBreaker redisCircuitBreaker;
    private final Retry redisRetry;

    public ResilienceConfig() {
        this.retryScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Nexus-Resilience-Retry-Scheduler");
            t.setDaemon(true);
            return t;
        });

        CircuitBreakerConfig mongoCbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(80.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(5)
                .recordExceptions(MongoException.class, MongoTimeoutException.class, MongoSocketException.class)
                .build();

        RetryConfig mongoRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .retryExceptions(MongoTimeoutException.class, MongoSocketException.class)
                .build();

        CircuitBreakerConfig redisCbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(30)
                .minimumNumberOfCalls(15)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(5)
                .recordExceptions(JedisException.class, JedisConnectionException.class)
                .build();

        RetryConfig redisRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(100), 2.0))
                .retryExceptions(JedisConnectionException.class)
                .build();

        this.circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        this.retryRegistry = RetryRegistry.ofDefaults();

        this.mongoCircuitBreaker = circuitBreakerRegistry.circuitBreaker(MONGO_INSTANCE, mongoCbConfig);
        this.mongoRetry = retryRegistry.retry(MONGO_INSTANCE, mongoRetryConfig);

        this.redisCircuitBreaker = circuitBreakerRegistry.circuitBreaker(REDIS_INSTANCE, redisCbConfig);
        this.redisRetry = retryRegistry.retry(REDIS_INSTANCE, redisRetryConfig);

        registerEventLoggers(mongoCircuitBreaker);
        registerEventLoggers(redisCircuitBreaker);
    }

    private void registerEventLoggers(CircuitBreaker cb) {
        cb.getEventPublisher()
                .onStateTransition(event -> LOGGER.warning(
                        "[Resilience/" + cb.getName() + "] state: "
                                + event.getStateTransition().getFromState()
                                + " -> " + event.getStateTransition().getToState()))
                .onCallNotPermitted(event -> LOGGER.log(Level.SEVERE,
                        "[Resilience/" + cb.getName() + "] call rejected — circuit is OPEN"))
                .onError(event -> LOGGER.fine(
                        "[Resilience/" + cb.getName() + "] recorded error: " + event.getThrowable()));
    }

    public CircuitBreaker mongoCircuitBreaker() {
        return mongoCircuitBreaker;
    }

    public Retry mongoRetry() {
        return mongoRetry;
    }

    public CircuitBreaker redisCircuitBreaker() {
        return redisCircuitBreaker;
    }

    public Retry redisRetry() {
        return redisRetry;
    }

    public ScheduledExecutorService retryScheduler() {
        return retryScheduler;
    }

    public CircuitBreaker.State mongoState() {
        return mongoCircuitBreaker.getState();
    }

    public CircuitBreaker.State redisState() {
        return redisCircuitBreaker.getState();
    }

    public void shutdown() {
        retryScheduler.shutdownNow();
    }
}
