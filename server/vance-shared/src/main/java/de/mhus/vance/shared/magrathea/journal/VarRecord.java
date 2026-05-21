package de.mhus.vance.shared.magrathea.journal;

import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User-defined workflow variable, written when a state's
 * {@code storeAs:} field is set. Generic key/value with a Jackson
 * {@link JsonNode} payload — see plan §3.2.1 for why we don't generate
 * a typed Record class per YAML.
 *
 * <p>The current value of a variable is the most recent
 * {@code VarRecord} with matching {@code key}; the projector walks the
 * journal in order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VarRecord implements JournalRecord {
    private String key;
    private JsonNode value;
}
