package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Three-state answer schema for inbox items. Carried both on the
 * persistent inbox item and on the {@code SteerMessage.InboxAnswer}
 * routed back to the originating process.
 *
 * <ul>
 *   <li>{@link AnswerOutcome#DECIDED} — substantive answer in
 *       {@link #value} (type-specific shape, see
 *       {@code user-interaction.md} §6).</li>
 *   <li>{@link AnswerOutcome#INSUFFICIENT_INFO} /
 *       {@link AnswerOutcome#UNDECIDABLE} — {@link #reason}
 *       explains; {@link #value} typically null.</li>
 * </ul>
 *
 * <p>{@link #answeredBy} is the user-id of the human responder, or
 * {@code "system:auto-default"} for {@link ResolvedBy#AUTO_DEFAULT},
 * or the worker process-id for {@link ResolvedBy#AUTO_RESOLVER}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class AnswerPayload {

    private AnswerOutcome outcome = AnswerOutcome.DECIDED;

    /**
     * Type-specific answer value (only when {@link #outcome} is
     * {@link AnswerOutcome#DECIDED}). Shape varies by item type:
     *
     * <ul>
     *   <li>{@code APPROVAL} → {@code {"approved": <bool>}}</li>
     *   <li>{@code DECISION} → {@code {"chosen": <option-value>, "freeText": <string-or-null>}}</li>
     *   <li>{@code FEEDBACK} → {@code {"text": <string>}}</li>
     *   <li>{@code ORDERING} → {@code {"orderedIds": [<string>]}}</li>
     *   <li>{@code STRUCTURE_EDIT} → {@code {"value": <json>}}</li>
     * </ul>
     */
    private @Nullable Map<String, Object> value;

    /**
     * Human-readable explanation for
     * {@link AnswerOutcome#INSUFFICIENT_INFO} or
     * {@link AnswerOutcome#UNDECIDABLE}. Should be specific enough
     * that the asking engine can react meaningfully (re-steer with
     * more context, escalate, …).
     */
    private @Nullable String reason;

    /**
     * Identifies the responder — userId for {@link ResolvedBy#USER},
     * synthetic value for the {@code AUTO_*} cases.
     */
    private String answeredBy = "";
}
