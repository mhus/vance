package de.mhus.vance.shared.document;

import de.mhus.vance.shared.document.kind.KindHandler;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Central inventory of valid document {@code kind} names. Spring
 * collects every {@link KindHandler} bean — both the built-ins from
 * {@link BuiltInKindHandlers} and addon-contributed ones (e.g. the
 * calendar addon's {@code CalendarKindHandler}) — and exposes their
 * names here.
 *
 * <p>Consulted by {@code doc_write} and friends to decide whether a
 * caller-supplied {@code kind} string is known and to fuzzy-resolve
 * typos / variants without throwing.
 *
 * <p>Names are stored lower-cased; lookups are case-insensitive.
 */
@Service
@Slf4j
public class KindRegistry {

    private final List<KindHandler> handlers;
    private Set<String> names = Set.of();
    private Map<String, KindHandler> byName = Map.of();
    /** Handlers in detection order — see {@link #detectKind}. */
    private List<KindHandler> detectionOrder = List.of();

    public KindRegistry(List<KindHandler> handlers) {
        this.handlers = handlers;
    }

    @PostConstruct
    void collect() {
        Set<String> collected = new LinkedHashSet<>();
        Map<String, KindHandler> handlerByName = new LinkedHashMap<>();
        for (KindHandler h : handlers) {
            String name = h.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "KindHandler " + h.getClass().getName() + " returned a blank name");
            }
            String key = name.toLowerCase();
            collected.add(key);
            KindHandler previous = handlerByName.putIfAbsent(key, h);
            if (previous != null) {
                log.warn("Duplicate KindHandler for kind '{}' — keeping {} , ignoring {}",
                        key, previous.getClass().getName(), h.getClass().getName());
            }
        }
        this.names = Collections.unmodifiableSet(collected);
        this.byName = Collections.unmodifiableMap(handlerByName);
        // Total, stable order: declared priority first, kind name as
        // tiebreaker. Built once here rather than per detectKind call —
        // the set is fixed after startup.
        this.detectionOrder = handlerByName.values().stream()
                .sorted(Comparator
                        .comparingInt(KindHandler::detectionPriority)
                        .thenComparing(KindHandler::getName))
                .toList();
    }

    /** All registered kind names, lower-cased, in registration order. */
    public Set<String> names() {
        return names;
    }

    /** True iff {@code kind} (case-insensitive) names a registered kind. */
    public boolean isKnown(@Nullable String kind) {
        if (kind == null) return false;
        return names.contains(kind.toLowerCase());
    }

    /**
     * The {@link KindHandler} bean for {@code name} (case-insensitive), or
     * {@code null} if no kind by that name is registered. When two handlers
     * claim the same name the first-registered wins (a warning is logged at
     * startup).
     */
    public @Nullable KindHandler handlerFor(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        return byName.get(name.toLowerCase());
    }

    /**
     * The kind that claims {@code content}, or {@code null} when none does.
     *
     * <p>For creates that carry no explicit {@code kind}. Handlers are asked
     * in {@link KindHandler#detectionPriority()} order, name-ascending on
     * ties, and the first claimant wins — short bodies are ambiguous by
     * nature, so a unique-claimant rule would simply not detect in the common
     * case. The order is declared on the kinds rather than inherited from
     * bean-injection order, so the same body yields the same kind regardless
     * of which addons happen to be deployed.
     *
     * <p>A detector that throws is treated as "does not claim": detection is
     * a convenience on the write path and must never fail a write. The
     * failure is logged, not propagated.
     */
    public @Nullable String detectKind(@Nullable String content) {
        if (content == null || content.isBlank()) return null;
        for (KindHandler h : detectionOrder) {
            try {
                if (h.detects(content)) {
                    return h.getName();
                }
            } catch (RuntimeException e) {
                log.warn("Kind detector '{}' failed — treated as no match: {}",
                        h.getName(), e.toString());
            }
        }
        return null;
    }
}
