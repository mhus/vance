package de.mhus.vance.shared.document.kind;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Shared JSON equivalence for the {@code *CodecParityTest} harness. Structural,
 * but numeric nodes compare <b>by value</b> so a Java {@code double} rendered
 * {@code 10.0} matches a JS integer {@code 10} (both are numbers) — while a
 * string {@code "10"} still differs from the number {@code 10}. This keeps the
 * parity signal on real structural/type drift and ignores the cosmetic
 * trailing-{@code .0} that Java doubles serialize with.
 */
final class ParityJson {

    private ParityJson() {}

    static boolean equivalent(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                JsonNode bv = b.get(e.getKey());
                if (bv == null || !equivalent(e.getValue(), bv)) return false;
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!equivalent(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        return a.equals(b);
    }
}
