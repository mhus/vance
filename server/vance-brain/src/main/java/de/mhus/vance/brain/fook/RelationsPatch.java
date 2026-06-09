package de.mhus.vance.brain.fook;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input to {@link FookTicketService#updateRelations}. Set the
 * scalar {@link #getDuplicateOf} to mark the target ticket as a
 * duplicate of another (replaces any prior value with the new
 * non-null one; pass {@code null} to leave it). The list-valued
 * fields are additive — existing entries on the target ticket are
 * preserved, the patch lists are merged in (de-duplicated). Use
 * empty lists to leave them untouched.
 */
@Value
@Builder
public class RelationsPatch {

    /** New value for {@code $meta.duplicateOf}. {@code null} means
     *  "don't change". To clear an existing value, callers would
     *  need a dedicated method — Fook never does that. */
    @Nullable String duplicateOf;

    /** UUIDs to append to {@code relations.rootCauseOf}. */
    List<String> addRootCauseOf;

    /** UUIDs to append to {@code relations.relatedTo}. */
    List<String> addRelatedTo;
}
