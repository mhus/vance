package de.mhus.vance.brain.eddie.activity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Append-only Vance Activity-Log row. Scoped to a user inside a tenant;
 * the {@code vanceProcessId} identifies which hub-session produced the
 * entry, so peers can filter to "everything except mine" when building
 * a recap.
 *
 * <p>"Nothing is ever deleted" — entries past the default 3-day recap
 * window stay in the collection and are accessible with an explicit
 * date filter. See {@code specification/vance-engine.md} §5.2 / §9.
 */
@Document(collection = "vance_activity")
@CompoundIndexes({
        @CompoundIndex(
                name = "user_ts_idx",
                def = "{ 'tenantId': 1, 'userId': 1, 'ts': -1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EddieActivityEntry {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** {@code UserDocument.name} of the user the hub belongs to. */
    private String userId = "";

    /** Hub-session id this entry was produced in. */
    private String sessionId = "";

    /** {@code ThinkProcessDocument.id} of the emitting hub-process. */
    private String vanceProcessId = "";

    private Instant ts = Instant.EPOCH;

    private EddieActivityKind kind = EddieActivityKind.NOTE;

    /**
     * One-line, voice-friendly description ("Projekt
     * naturkatastrophen angelegt mit Marvin-Engine"). Peers paste
     * this verbatim into recaps.
     */
    private String summary = "";

    /** Pointers to entities referenced by this entry. */
    @Builder.Default
    private List<EntityRef> refs = new ArrayList<>();
}
