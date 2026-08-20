package de.mhus.vance.toolpack.facet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the reader picked, as {@code key → values}.
 *
 * <p>Not a type of its own but a set of rules over a plain map, because both
 * contracts carry the selection inside their own request record and a wrapper
 * would only add a name to unwrap. What has to be shared is the meaning:
 *
 * <ul>
 *   <li><b>Conjunction across keys, disjunction within one.</b>
 *       {@code {origin-place: [m49:142], origin-topic: [gaming]}} is „Asian
 *       <em>and</em> gaming"; {@code {origin-place: [m49:142, m49:150]}} is
 *       „Asian <em>or</em> European". That is what every facet UI renders, and
 *       the only reading that needs no brackets.
 *   <li><b>Only declared keys travel.</b> {@link #restrictTo} narrows a
 *       selection to what a source said it understands, and
 *       {@link #undeclaredKeys} names the rest — which is not a filter to
 *       apply locally but the reason to leave that source out of the request.
 * </ul>
 */
public final class FacetSelection {

    private FacetSelection() {
        /* static only */
    }

    /** An empty selection — no facet filtering. */
    public static Map<String, List<String>> none() {
        return Map.of();
    }

    /**
     * Immutable copy with blank keys and values dropped and everything
     * trimmed. A key whose values are all blank disappears entirely: an empty
     * value list would otherwise read as „match nothing" at one call site and
     * „match everything" at the next.
     */
    public static Map<String, List<String>> normalize(
            @Nullable Map<String, List<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            String key = e.getKey() == null ? null : e.getKey().trim();
            if (key == null || key.isEmpty() || e.getValue() == null) {
                continue;
            }
            Set<String> values = new LinkedHashSet<>();
            for (String v : e.getValue()) {
                if (v != null && !v.isBlank()) {
                    values.add(v.trim());
                }
            }
            if (!values.isEmpty()) {
                out.put(key, List.copyOf(values));
            }
        }
        return Map.copyOf(out);
    }

    /** The part of {@code selection} a source declaring {@code declared} can answer. */
    public static Map<String, List<String>> restrictTo(
            Map<String, List<String>> selection, Set<String> declared) {
        if (selection.isEmpty() || declared.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : selection.entrySet()) {
            if (declared.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Selected keys the source did not declare — sorted, so a log line or a
     * note reads the same twice.
     */
    public static List<String> undeclaredKeys(
            Map<String, List<String>> selection, Set<String> declared) {
        if (selection.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String key : selection.keySet()) {
            if (!declared.contains(key)) {
                out.add(key);
            }
        }
        out.sort(String::compareTo);
        return List.copyOf(out);
    }

    /** The keys a list of declared facets covers. */
    public static Set<String> keysOf(List<Facet> facets) {
        Set<String> out = new LinkedHashSet<>();
        for (Facet facet : facets) {
            out.add(facet.key());
        }
        return Set.copyOf(out);
    }
}
