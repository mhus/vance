package de.mhus.vance.api.zem;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One typed question to a decision model.
 *
 * <p>{@code instructions} and the descriptions inside {@code criteria}
 * may be a string, a map or a list — System One accepts all three, and
 * a structured description (fields like {@code what}, {@code not_for},
 * {@code examples}) is the documented way to sharpen an option. Whether
 * a model accepts structured <i>criteria</i> is a per-model catalog fact
 * ({@code structuredCriteria}); structured {@code instructions} are
 * accepted everywhere.
 *
 * <p>Shape of {@code criteria} per type:
 * <ul>
 *   <li>{@link ZemQuestionType#NOUL}: optional {@code {true: …, false: …}}
 *       — both sides or none (one-sided criteria are rejected by some
 *       providers, so the rule holds everywhere)</li>
 *   <li>{@link ZemQuestionType#CHOICE}: required map
 *       {@code option -> description | null}, insertion order kept</li>
 *   <li>{@link ZemQuestionType#SCORE}: required list of level
 *       descriptions, index 0 = lowest</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("zem")
public class ZemQuestionDto {

    /** Question key — becomes the answer key. No dots: the name is
     *  persisted as a Mongo map key (recipe params, run state). */
    @NotBlank
    @Pattern(regexp = "[a-z0-9_-]{1,64}")
    private String name;

    @NotNull
    private ZemQuestionType type;

    /** The question itself — string, map or list. */
    @NotNull
    private Object instructions;

    /** Type-dependent option/level descriptions, see class doc. */
    private @Nullable Object criteria;
}
