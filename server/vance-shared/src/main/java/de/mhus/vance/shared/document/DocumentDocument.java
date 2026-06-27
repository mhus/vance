package de.mhus.vance.shared.document;

import de.mhus.vance.api.common.AccentColor;
import de.mhus.vance.api.documents.WriterRole;
import java.time.Instant;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent document record. Scoped to a tenant + project; addressed by
 * {@code path} inside the project.
 *
 * <p>Exactly one of {@link #inlineText} or {@link #storageId} is populated:
 * small text documents stay inline; everything else lives in
 * {@code StorageService}. The {@link #size} field always reflects the logical
 * (uncompressed, un-encoded) byte size of the content.
 */
@Document(collection = "documents")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_project_path_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'path': 1 }",
                unique = true),
        @CompoundIndex(
                name = "tenant_project_status_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'status': 1 }"),
        @CompoundIndex(
                name = "tenant_project_summary_claim_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'summaryDirty': 1, 'autoSummary': 1, 'claimedBy': 1 }"),
        @CompoundIndex(
                name = "tenant_project_rag_claim_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'ragDirty': 1, 'ragClaimedBy': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Owning project ({@code ProjectDocument.name}). */
    private String projectId = "";

    /** Virtual path inside the project, e.g. {@code "notes/thesis/ch1.md"}. */
    private String path = "";

    /** File-name portion of {@link #path} — derived on create, kept for indexed lookups. */
    private String name = "";

    /** Human-readable title. Nullable; UI falls back to {@link #name}. */
    private @Nullable String title;

    /**
     * Accent color from the restricted 12-value palette ({@link AccentColor}).
     * {@code null} means no color set; the UI renders neutral.
     */
    private @Nullable AccentColor color;

    /** Orthogonal tag set — the second organizing axis next to {@link #path}. */
    @Indexed
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** e.g. {@code "text/markdown"}, {@code "application/pdf"}. */
    private @Nullable String mimeType;

    /** Logical content size in bytes. */
    private long size;

    /** Storage id ({@code StorageService}) — the body lives there exclusively.
     *  Sparse-indexed: inline-text documents leave this {@code null} and aren't
     *  carried in the index. Looked up by the storage-orphan sweep in batches. */
    @Indexed(sparse = true)
    private @Nullable String storageId;

    /**
     * Whether the blob behind {@link #storageId} was gzip-compressed before it
     * was written. {@link DocumentService#loadContent} transparently wraps a
     * {@code GZIPInputStream} when this is {@code true}. Independent of
     * {@link #size}, which always carries the original uncompressed byte
     * count. Legacy documents without this field read as {@code false}.
     */
    private boolean compressed;

    /**
     * Mirror of the {@code kind:} front-matter value for markdown documents.
     * {@code null} when the body is not markdown or carries no front matter.
     * Indexed so the list endpoint can filter by document kind.
     */
    @Indexed
    private @Nullable String kind;

    /**
     * Mirror of every parsed front-matter line for markdown documents — kept
     * in source order. Keys are normalised by {@link DocumentHeaderParser}
     * (lower-case, dots→underscores) so MongoDB does not interpret them as
     * sub-document paths. The body remains the source of truth: this map is
     * rebuilt on every save.
     */
    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();

    /** Username of the creator ({@code UserDocument.name}). */
    private @Nullable String createdBy;

    /**
     * Stable identifier shared by the live document and every archived
     * version of it. Survives renames, restores, and inline⇄storage
     * transitions. Assigned at create-time and never overwritten.
     */
    @Indexed
    private String lineageId = "";

    /**
     * Wall-clock at which the last archive entry was written from this
     * document. {@code null} on a fresh document (no archives yet);
     * compared against {@code documents.archive.minVersionInterval} on
     * the next save to decide whether to archive again.
     */
    private @Nullable Instant lastArchivedAt;

    /**
     * Optimistic-locking guard — used by Spring Data MongoDB to reject
     * concurrent overwrites. Prevents the archive-then-overwrite
     * sequence in {@link DocumentService#update} from racing with a
     * parallel save and producing a torn version chain.
     */
    @Version
    private @Nullable Long version;

    @Builder.Default
    private DocumentStatus status = DocumentStatus.ACTIVE;

    @CreatedDate
    private @Nullable Instant createdAt;

    /**
     * Opt-in marker for the auto-summary scheduler. Default is set in
     * {@link DocumentService#create} from the document's mime-type
     * (text/markdown + text/plain start out true, everything else false).
     * User-editable afterwards.
     */
    private boolean autoSummary;

    /**
     * Dirty flag for the auto-summary scheduler — set in
     * {@link DocumentService#update} when inline content changes,
     * cleared by {@link DocumentService#writeSummary} after the
     * scheduler successfully produced a summary.
     */
    private boolean summaryDirty;

    /** LLM-generated summary, written by the auto-summary driver. */
    private @Nullable String summary;

    /** When the summary was last produced. */
    private @Nullable Instant summarizedAt;

    /** Pod that currently holds the summary claim ({@code null} = unclaimed). */
    private @Nullable String claimedBy;

    /** When the summary claim was acquired — used for stale-claim recovery. */
    private @Nullable Instant claimedAt;

    /**
     * User-override for project-RAG indexing. {@code null} (default) means
     * "auto" — the indexer applies the rule "path starts with documents/ AND
     * mime-type is textual". {@code true} forces indexing, {@code false}
     * excludes the document. See {@code specification/rag.md}.
     */
    private @Nullable Boolean ragEnabled;

    /**
     * Dirty flag for the project-RAG indexer — set by
     * {@link DocumentService#create}/{@link DocumentService#update} when the
     * document is eligible and content changed; cleared by
     * {@link DocumentService#markRagClean} after the indexer wrote chunks.
     */
    private boolean ragDirty;

    /** Pod that currently holds the RAG-index claim ({@code null} = unclaimed). */
    private @Nullable String ragClaimedBy;

    /** When the RAG-index claim was acquired — used for stale-claim recovery. */
    private @Nullable Instant ragClaimedAt;

    // ─── Script Cortex deep-validate cache (see planning/script-cortex.md) ───

    /**
     * SHA-256 hex of the {@link #inlineText} that was deep-validated by an
     * LLM review. Used by the Script Cortex UI to mark the document as
     * "still reviewed" when the current content hashes to the same value.
     */
    private @Nullable String lastDeepReviewedHash;

    /** Serialized JSON array of {@code ScriptDeepWarning}s from the last review. */
    private @Nullable String lastDeepReviewWarningsJson;

    /** When the LLM produced the cached deep-review. */
    private @Nullable Instant lastDeepReviewedAt;

    /**
     * MongoDB TTL — once this timestamp passes, the document row is
     * removed by the server-side TTL monitor. {@code null} (the default
     * for normal documents) disables expiry: Mongo's TTL index skips
     * documents whose date field is absent. Currently set by the
     * scheduler-log writer (7d retention) and reserved for similar
     * "ephemeral diagnostics" use cases.
     *
     * <p>Important: the TTL applies to the document row itself, not just
     * its content — a deletion via TTL is final, no archive or trash
     * step intervenes.
     */
    @Indexed(expireAfterSeconds = 0)
    private @Nullable Instant expiresAt;

    /**
     * Sticky-notes annotating this document. Keyed by note id (also
     * stored on the value as {@link DocumentNote#getId()} for convenience
     * when the map is unwrapped to a list).
     *
     * <p>Mutated through {@link DocumentService}'s atomic
     * {@code addNote}/{@code updateNote}/{@code deleteNote} methods —
     * the field is never written via a full {@code save(doc)}, so a
     * note edit never produces a new archive entry. Hard-capped at
     * {@link DocumentService#NOTES_MAX} entries per document; further
     * adds are rejected.
     *
     * <p>Carried into the archive snapshot as-is when an archive is
     * created from a content change — note history is implicit in the
     * version history.
     */
    private Map<String, DocumentNote> notes = new LinkedHashMap<>();

    /**
     * Soft edit-protection — writer roles that are blocked from
     * mutating this document. Empty/null means no lock; non-empty means
     * a write whose {@code WriterRole} (derived from the
     * {@code WriterIdentity}) is in this set is rejected with
     * {@code DocumentLockedException}.
     *
     * <p>Set is normalised by {@code DocumentService.setLockedFor}: when
     * {@code USER} or {@code KIT} is in the input, {@code AI} is auto-
     * added. Initially seeded at create-time from the body's
     * {@code $meta.lockedForInitial} header.
     *
     * <p>Not mutated by content updates — only by the dedicated
     * {@code PATCH /lock} endpoint, the {@code document_lock_*} LLM
     * tools, and the Kit-Apply seed path. See
     * {@code planning/document-lock-level.md}.
     */
    @Builder.Default
    private Set<WriterRole> lockedFor = EnumSet.noneOf(WriterRole.class);
}
