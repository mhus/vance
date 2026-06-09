package de.mhus.vance.brain.fook;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Parsed shape of the JSON the {@code fook} recipe LLM returns —
 * see {@code _vance/recipes/fook.yaml}. Tagged union over
 * {@link Decision}; only the fields relevant to the picked decision
 * are populated.
 *
 * <p>The {@code derived*} fields on a {@link Decision#NEW_TICKET}
 * result come entirely from the LLM's analysis of the free-form
 * report text — the reporter never supplies type/title/severity.
 *
 * <p>The {@link #getCategory} enum carries the discard reason. The
 * recipe enumerates the allowed values; this field accepts any
 * string so a future recipe revision can add categories without a
 * coordinated code change.
 */
@Value
@Builder
public class TriageResult {

    public enum Decision { NEW_TICKET, MERGE_INTO, DISCARD }

    Decision decision;

    /** new_ticket only — LLM-derived from the report text. */
    @Nullable String derivedType;
    /** new_ticket only — LLM-derived from the report text. */
    @Nullable String derivedSeverity;
    /** new_ticket only — LLM-derived from the report text. */
    @Nullable String derivedTitle;

    /** merge_into only — UUID of the ticket the submission folds into. */
    @Nullable String targetTicketId;
    /** merge_into only — {@code duplicateOf}, {@code rootCauseOf} or
     *  {@code relatedTo}. */
    @Nullable String relation;

    /** new_ticket + merge_into — optional extra links. */
    List<String> relatedTickets;

    /** discard only — one of {@code project_data},
     *  {@code documentation_question}, {@code unrelated},
     *  {@code nonsense}, {@code self_loop}, {@code other}. */
    @Nullable String category;

    /** new_ticket + merge_into — optional triage notes copied into
     *  the ticket's body. */
    @Nullable String triageNote;

    /** One sentence for the user-facing inbox item, always set. */
    @Nullable String reason;
}
