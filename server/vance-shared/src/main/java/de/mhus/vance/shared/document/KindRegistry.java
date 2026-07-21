package de.mhus.vance.shared.document;

import de.mhus.vance.shared.document.kind.KindHandler;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
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
 * <p>Consulted by {@code doc_create} and friends to decide whether a
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
}
