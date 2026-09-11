package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of checking a project's kit-source setup — whether the authoring
 * manifest, its descriptor and the artefacts it lists are consistent
 * enough that {@code export} (or an install straight from this project)
 * would do what the author intends.
 *
 * <p>Errors are things that make the setup not work as stated; warnings
 * are things that work but deserve a look. Both are plain sentences —
 * the consumer is an LLM or an operator, and the point is to fix, not to
 * re-derive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitAuthoringValidationDto {

    private boolean valid;

    @Builder.Default
    private List<String> errors = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    private @Nullable String kitName;

    private @Nullable String kitVersion;

    /** Artefact counts as the manifest states them — not what exists. */
    private int documents;

    private int settings;

    private boolean hasEncryptedSecrets;

    private @Nullable String originUrl;
}
