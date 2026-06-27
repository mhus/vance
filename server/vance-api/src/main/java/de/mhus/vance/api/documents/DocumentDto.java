package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.common.AccentColor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full document representation returned by
 * {@code GET /brain/{tenant}/documents/{id}}.
 *
 * <p>{@link #inlineText} carries the content for inline-stored documents;
 * documents backed by storage have {@code inlineText == null} and an
 * {@code inline = false} flag. The v1 UI does not download storage-backed
 * content.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentDto {

    private String id;

    private String projectId;

    private String path;

    private String name;

    private @Nullable String title;

    /** Accent color from the restricted 12-value palette; {@code null} means no color set. */
    private @Nullable AccentColor color;

    private @Nullable String mimeType;

    private long size;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private @Nullable Long createdAtMs;

    private @Nullable String createdBy;

    /**
     * Pure client-side cache flag — the server never sets this since the
     * full-storage migration. The web/mobile editor flips it to {@code true}
     * after streaming the body via {@code GET /documents/{id}/content} so
     * downstream views can read {@link #inlineText} without re-fetching.
     */
    @Deprecated
    private boolean inline;

    /**
     * Pure client-side cache for the body — the server never sets this.
     * Populated by the web/mobile editor after a streamed
     * {@code /content} fetch so the existing view layer can read the body
     * synchronously. To be removed once the editor views are refactored to
     * accept the body as an explicit prop.
     */
    @Deprecated
    private @Nullable String inlineText;

    /**
     * {@code kind:} value parsed from markdown front matter — e.g. {@code "list"},
     * {@code "tree"}, {@code "mindmap"}. {@code null} when the document is not
     * markdown or carries no front matter.
     */
    private @Nullable String kind;

    /**
     * Every parsed front-matter line, in source order. Empty when the document
     * has no front matter. Keys are normalised: lower-cased, with dots replaced
     * by underscores so MongoDB does not interpret them as path separators.
     */
    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();

    /** Per-document opt-in for the auto-summary scheduler — see {@code readme/auto-summary.md}. */
    private boolean autoSummary;

    /** Dirty flag picked up by the auto-summary scheduler on the next tick. */
    private boolean summaryDirty;

    /** Most recent LLM-generated summary; {@code null} until the driver wrote one. */
    private @Nullable String summary;

    /** Wall-clock at which the current {@link #summary} was produced. */
    private @Nullable Long summarizedAtMs;

    /**
     * Project-RAG inclusion override. {@code null} = auto (default —
     * include if path starts with {@code documents/} and mime is textual).
     * {@code true} = always include. {@code false} = never include.
     * See {@code specification/rag.md}.
     */
    private @Nullable Boolean ragEnabled;

    // ─── Script Cortex deep-validate cache ───

    /** SHA-256 hex of the inline content the cached LLM review was based on. */
    private @Nullable String lastDeepReviewedHash;

    /** Cached LLM review warnings — JSON array, parsed client-side. */
    private @Nullable String lastDeepReviewWarningsJson;

    /** Epoch ms of the cached review. */
    private @Nullable Long lastDeepReviewedAtMs;

    /**
     * Epoch ms at which the MongoDB TTL monitor will delete the document.
     * {@code null} for normal documents (no expiry). Set by server-side
     * writers such as the scheduler-log retention path.
     */
    private @Nullable Long expiresAtMs;

    /**
     * Sticky-notes embedded in this document — keyed by note id (also
     * present as {@code DocumentNoteDto.id}). Mutated through dedicated
     * {@code /notes} REST endpoints; never via {@code PUT /documents/{id}}.
     * Empty when the document has no notes.
     */
    private Map<String, DocumentNoteDto> notes = new LinkedHashMap<>();

    /**
     * Soft edit-protection — writer roles that are blocked from
     * mutating this document. {@code null} or empty means no lock.
     * Serialised as a sorted array so diffs are stable across saves.
     *
     * <p>The set is normalised server-side (see
     * {@code DocumentService.setLockedFor}): {@code AI} is auto-added
     * whenever {@code USER} or {@code KIT} is present. Mutated through
     * the dedicated {@code PATCH /lock} endpoint and the
     * {@code document_lock_*} LLM tools — never via
     * {@code PUT /documents/{id}}.
     */
    @Builder.Default
    private Set<WriterRole> lockedFor = EnumSet.noneOf(WriterRole.class);
}
