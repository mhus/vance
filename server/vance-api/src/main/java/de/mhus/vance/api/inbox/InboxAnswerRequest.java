package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code inbox-answer}. The 3-state outcome plus the
 * type-specific value or reason.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxAnswerRequest {

    @NotBlank
    private String itemId;

    private AnswerOutcome outcome = AnswerOutcome.DECIDED;

    /** Required when {@link #outcome} is {@link AnswerOutcome#DECIDED}. */
    private @Nullable Map<String, Object> value;

    /** Required when {@link #outcome} is
     *  {@link AnswerOutcome#INSUFFICIENT_INFO} or
     *  {@link AnswerOutcome#UNDECIDABLE}. */
    private @Nullable String reason;
}
