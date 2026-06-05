package de.mhus.vance.shared.document;

import de.mhus.vance.shared.document.kind.KindHandler;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
public class KindRegistry {

    private final List<KindHandler> handlers;
    private Set<String> names = Set.of();

    public KindRegistry(List<KindHandler> handlers) {
        this.handlers = handlers;
    }

    @PostConstruct
    void collect() {
        Set<String> collected = new LinkedHashSet<>();
        for (KindHandler h : handlers) {
            String name = h.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "KindHandler " + h.getClass().getName() + " returned a blank name");
            }
            collected.add(name.toLowerCase());
        }
        this.names = Collections.unmodifiableSet(collected);
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
}
