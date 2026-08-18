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

    /**
     * Identity of the install record this operation wrote, i.e. the file
     * name under {@code _vance/kits/installed/}. Null for {@code APPLY}
     * and {@code EXPORT}, which do not track anything.
     */
    private @Nullable String kitId;

    /** {@code INSTALL}, {@code UPDATE}, {@code APPLY}, {@code EXPORT}. */
    private String mode;

    /**
     * Version of the kit that was installed, as its descriptor declares it.
     *
     * <p>Callers used to read this off {@link #sourceCommit}, which happens
     * to spell {@code library:3.1.0} for a store kit — and a bare SHA for a
     * git one, where there is no version to recover at all. Two different
     * questions were answered by one string, so the display of "updated to
     * X" worked for one source and quietly failed for the other.
     *
     * <p>Null only where the operation never got as far as a descriptor —
     * an update that failed before resolving.
     */
    private @Nullable String version;

    /** SHA of the source commit at the time of the operation. */
    private @Nullable String sourceCommit;

    @Builder.Default
    private List<String> documentsAdded = new ArrayList<>();

    @Builder.Default
    private List<String> documentsUpdated = new ArrayList<>();

    @Builder.Default
    private List<String> documentsRemoved = new ArrayList<>();

    /**
     * Documents skipped because they are KIT-locked (soft-lock —
     * {@code lockedFor} contains {@code KIT}). The kit installer leaves
     * the existing content untouched and reports the path here so the
     * operator can audit what was not refreshed. See
     * {@code planning/document-lock-level.md} §6.
     */
    @Builder.Default
    private List<String> documentsSkipped = new ArrayList<>();

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

    /**
     * Documents left untouched because the kit's policy said so —
     * {@code KEEP} on a locally modified file, or {@code IGNORE}.
     * Deliberately separate from {@link #documentsSkipped} (KIT lock):
     * both mean "not written", but the operator needs to know which of
     * the two it was.
     */
    @Builder.Default
    private List<String> documentsSkippedByPolicy = new ArrayList<>();

    /** Setting keys left untouched by the kit's policy. */
    @Builder.Default
    private List<String> settingsSkippedByPolicy = new ArrayList<>();

    /**
     * Documents whose three-way merge conflicted. The original is
     * untouched; the merged text with conflict markers sits beside it as
     * {@code <path>.kit-merge} for the user to resolve.
     */
    @Builder.Default
    private List<String> documentsConflicted = new ArrayList<>();

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
