package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * File + optional 1-based line range, used inside {@link IdeContext} to
 * point at editor selections / mentions on the foot host.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class IdeFileRange {

    /** Absolute path on the foot host. */
    @NotBlank
    private String filePath;

    /** 1-based start line, inclusive. {@code null} when the whole file is meant. */
    private @Nullable Integer lineStart;

    /** 1-based end line, inclusive. {@code null} when the whole file is meant. */
    private @Nullable Integer lineEnd;
}
