package de.mhus.vance.shared.lease;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Occupancy counter for one funnel level of one resource —
 * {@code _id = <resource>|<level>|<scope>} (e.g. {@code tex2pdf|tenant|acme}).
 *
 * <p>Acquisition is one atomic conditional
 * {@code findOneAndUpdate({_id, count: {$lt: cap}}, {$inc: {count: 1}})}
 * without upsert: a matching document increments, a miss means "full" —
 * unless the document does not exist yet, which a pure-{@code _id} upsert
 * at {@code count: 0} fixes before one more try. Reconcile removes
 * counters that no holder books. No further indexes: {@code _id} is the
 * only access path.
 */
@Document(collection = "resource_lease_counters")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLeaseCounterDocument {

    /** {@code <resource>|<level>|<scope>} — {@code |global|} for the global level. */
    @Id
    private String id;

    /** Currently held slots on this level. */
    private long count;

    /** Last write — reconcile only corrects counters quiet for a safety window. */
    private Instant updatedAt;

    public ResourceLeaseCounterDocument(String id, long count) {
        this.id = id;
        this.count = count;
        this.updatedAt = null;
    }
}
