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
 * Outcome of a kit operation — what was added, updated, removed,
 * skipped. Returned by {@code KitService.install/update/apply/export}
 * and surfaced to the caller (CLI, tool response, web).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitOperationResultDto {

    private String kitName;

    /** {@code INSTALL}, {@code UPDATE}, {@code APPLY}, {@code EXPORT}. */
    private String mode;

    /** SHA of the source commit at the time of the operation. */
    private @Nullable String sourceCommit;

    @Builder.Default
    private List<String> documentsAdded = new ArrayList<>();

    @Builder.Default
    private List<String> documentsUpdated = new ArrayList<>();

    @Builder.Default
    private List<String> documentsRemoved = new ArrayList<>();

    @Builder.Default
    private List<String> settingsAdded = new ArrayList<>();

    @Builder.Default
    private List<String> settingsUpdated = new ArrayList<>();

    @Builder.Default
    private List<String> settingsRemoved = new ArrayList<>();

    @Builder.Default
    private List<String> toolsAdded = new ArrayList<>();

    @Builder.Default
    private List<String> toolsUpdated = new ArrayList<>();

    @Builder.Default
    private List<String> toolsRemoved = new ArrayList<>();

    /** PASSWORD-setting keys skipped due to vault decryption failure. */
    @Builder.Default
    private List<String> skippedPasswords = new ArrayList<>();

    /** Inherit chain that was resolved to assemble the kit. */
    @Builder.Default
    private List<String> inheritedKits = new ArrayList<>();

    /** Free-form warnings (e.g. inherit-cycle, missing vault-pw). */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
