package de.mhus.vance.shared.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Centralized service for creating and retrieving Micrometer metric
 * instruments. Wraps a {@link MeterRegistry} so callers don't have to
 * keep references to individual instruments — looked up by name+tags
 * on every call, cached internally to avoid re-registration overhead.
 *
 * <p>Usage:
 * <pre>
 *   metricService.counter("llm.calls.total", "provider", "anthropic").increment();
 *   metricService.timer("llm.calls.duration", "provider", "anthropic")
 *                .record(Duration.ofMillis(845));
 *   metricService.gauge("processes.active", "engine", "arthur",
 *                       () -&gt; thinkProcessService.countActive("arthur"));
 *   metricService.summary("chat.message.size", "role", "USER").record(bytes);
 *   metricService.exception(this.getClass(), "doStuff", ex);
 * </pre>
 *
 * <p>Patterned after {@code nimbus-shared.MetricService} — same API so
 * authors moving between projects don't have to re-learn it.
 * Instrument caches are bounded ({@link #MAX_ENTRIES_PER_TYPE}) with a
 * WARN log when the limit is approached, since unbounded tag cardinality
 * is the classical Prometheus footgun.
 */
@Service
@Slf4j
public class MetricService {

    /** Per-instrument-type cache ceiling; warn (not fail) on overflow. */
    private static final int MAX_ENTRIES_PER_TYPE = 10_000;

    private final MeterRegistry registry;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DistributionSummary> summaries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GaugeHolder> gauges = new ConcurrentHashMap<>();

    public MetricService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Gets or creates a Counter.
     *
     * @param name Metric name (e.g. {@code "vance.llm.calls"})
     * @param tags Tag key-value pairs (e.g. {@code "provider", "anthropic"})
     * @return the Counter instance
     */
    public Counter counter(String name, String... tags) {
        String key = buildKey(name, tags);
        return counters.computeIfAbsent(key, k -> {
            checkCapacity(counters, "counter", name);
            return Counter.builder(name)
                    .tags(toTags(tags))
                    .register(registry);
        });
    }

    /**
     * Gets or creates a Timer for duration tracking.
     */
    public Timer timer(String name, String... tags) {
        String key = buildKey(name, tags);
        return timers.computeIfAbsent(key, k -> {
            checkCapacity(timers, "timer", name);
            return Timer.builder(name)
                    .tags(toTags(tags))
                    .register(registry);
        });
    }

    /**
     * Gets or creates a {@link DistributionSummary} for value
     * distribution tracking (e.g. payload sizes, token counts).
     */
    public DistributionSummary summary(String name, String... tags) {
        String key = buildKey(name, tags);
        return summaries.computeIfAbsent(key, k -> {
            checkCapacity(summaries, "summary", name);
            return DistributionSummary.builder(name)
                    .tags(toTags(tags))
                    .register(registry);
        });
    }

    /**
     * Registers a Gauge backed by a supplier. The supplier is held via
     * a {@link SoftReference} so it can be GC'd if memory is tight;
     * the gauge then falls back to the last sampled value held in the
     * returned {@link AtomicReference}. Callers may also push values
     * into that reference directly to manually update the gauge.
     */
    public AtomicReference<Double> gauge(String name, String[] tags, Supplier<Number> supplier) {
        String key = buildKey(name, tags);
        return gauges.computeIfAbsent(key, k -> {
            checkCapacity(gauges, "gauge", name);
            return new GaugeHolder(name, toTags(tags), supplier, registry);
        }).getValue();
    }

    /** Convenience gauge with varargs tags. */
    public AtomicReference<Double> gauge(String name, Supplier<Number> supplier, String... tags) {
        return gauge(name, tags, supplier);
    }

    /**
     * Records an exception occurrence with source class and context
     * keys. Surfaces as
     * {@code vance.exceptions{source=X,context=Y,type=Z}} counter.
     */
    public void exception(Class<?> source, String context, Throwable exception) {
        String type = exception.getClass().getSimpleName();
        counter("vance.exceptions",
                "source", source.getSimpleName(),
                "context", context,
                "type", type).increment();
    }

    /** Records an exception with source class only. */
    public void exception(Class<?> source, Throwable exception) {
        String type = exception.getClass().getSimpleName();
        counter("vance.exceptions",
                "source", source.getSimpleName(),
                "type", type).increment();
    }

    /** Records an exception with context string only (no source class). */
    public void exception(String context, Throwable exception) {
        String type = exception.getClass().getSimpleName();
        counter("vance.exceptions",
                "context", context,
                "type", type).increment();
    }

    /** Returns the underlying registry for advanced use cases. */
    public MeterRegistry getRegistry() {
        return registry;
    }

    // ─── Internals ──────────────────────────────────────────────────

    /**
     * Composite cache key: {@code name + NUL + sorted(tagKey, tagVal, …)}.
     * Sorting keeps the key stable regardless of the caller's tag order.
     */
    static String buildKey(String name, String... tags) {
        if (tags == null || tags.length == 0) return name;
        String[] sorted = Arrays.copyOf(tags, tags.length);
        // Pair-sort by key (even indices) — small N, bubble is fine.
        for (int i = 0; i < sorted.length - 2; i += 2) {
            for (int j = i + 2; j < sorted.length; j += 2) {
                if (sorted[i].compareTo(sorted[j]) > 0) {
                    String tmpKey = sorted[i];
                    String tmpVal = sorted[i + 1];
                    sorted[i] = sorted[j];
                    sorted[i + 1] = sorted[j + 1];
                    sorted[j] = tmpKey;
                    sorted[j + 1] = tmpVal;
                }
            }
        }
        StringBuilder sb = new StringBuilder(name);
        for (String s : sorted) {
            sb.append('\0').append(s);
        }
        return sb.toString();
    }

    private Tags toTags(String... tags) {
        if (tags == null || tags.length == 0) return Tags.empty();
        return Tags.of(tags);
    }

    private <V> void checkCapacity(Map<String, V> map, String type, String name) {
        if (map.size() >= MAX_ENTRIES_PER_TYPE) {
            log.warn("MetricService: {} cache at capacity ({}) — new metric '{}'"
                            + " may indicate tag cardinality issue",
                    type, MAX_ENTRIES_PER_TYPE, name);
        }
    }

    /**
     * Holds a gauge registration with a {@link SoftReference} to the
     * supplier. After GC, the gauge falls back to the last sampled
     * value in {@link #value}.
     */
    private static class GaugeHolder {
        private final AtomicReference<Double> value = new AtomicReference<>(0.0);
        private final SoftReference<Supplier<Number>> supplierRef;

        GaugeHolder(String name, Tags tags, Supplier<Number> supplier, MeterRegistry registry) {
            this.supplierRef = new SoftReference<>(supplier);
            Number initial = supplier.get();
            if (initial != null) value.set(initial.doubleValue());

            Gauge.builder(name, this, GaugeHolder::currentValue)
                    .tags(tags)
                    .register(registry);
        }

        double currentValue() {
            Supplier<Number> supplier = supplierRef.get();
            if (supplier != null) {
                Number v = supplier.get();
                if (v != null) {
                    value.set(v.doubleValue());
                }
            }
            return value.get();
        }

        AtomicReference<Double> getValue() {
            return value;
        }
    }
}
