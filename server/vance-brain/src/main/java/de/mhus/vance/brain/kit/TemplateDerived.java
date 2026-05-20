package de.mhus.vance.brain.kit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side derived variable in a {@code template.yaml}. Evaluated by
 * the {@link TemplateApplier} after input validation, before document
 * substitution — the result is added to the {@code {{var:<name>}}}
 * lookup table.
 *
 * <p>Currently only the {@code union} kind is supported: gather list
 * items from a multi-select input ({@link #from}), with optional
 * always-included {@link #base} values. The rendered substitution is a
 * JSON-encoded array (also valid as a YAML flow sequence).
 *
 * <p>YAML schema:
 * <pre>
 *   derived:
 *     - name: oauth_scopes
 *       kind: union
 *       from: features                  # name of a multi-select input
 *       base: [read:me, offline_access]
 *       perChoice:
 *         jira: [read:jira-work, write:jira-work]
 *         confluence: [read:confluence-content.all]
 * </pre>
 *
 * @param name      variable name — referenced as {@code {{var:<name>}}}
 *                  in documents
 * @param kind      computation kind (currently only {@link Kind#UNION})
 * @param from      name of the multi-select input whose value drives
 *                  the per-choice lookup
 * @param base      values always included in the union, regardless of
 *                  selection (may be empty)
 * @param perChoice map from choice value to its contribution to the union
 *                  (entries for unselected choices are skipped at apply)
 */
public record TemplateDerived(
        String name,
        Kind kind,
        String from,
        List<String> base,
        Map<String, List<String>> perChoice) {

    public enum Kind {
        UNION;

        public static Kind parse(String raw, String fieldLabel) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "derived '" + fieldLabel + "': 'kind' is required");
            }
            String token = raw.trim().toUpperCase().replace('-', '_');
            try {
                return Kind.valueOf(token);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "derived '" + fieldLabel + "': unknown kind '" + raw
                                + "' — expected one of " + java.util.Arrays.toString(values()).toLowerCase());
            }
        }
    }

    public TemplateDerived {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("derived: 'name' is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("derived '" + name + "': 'kind' is required");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException(
                    "derived '" + name + "': 'from' (multi-select input name) is required");
        }
        base = base == null ? List.of() : List.copyOf(base);
        if (perChoice == null) {
            perChoice = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : perChoice.entrySet()) {
                copy.put(e.getKey(),
                        e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
            }
            perChoice = Map.copyOf(copy);
        }
    }
}
