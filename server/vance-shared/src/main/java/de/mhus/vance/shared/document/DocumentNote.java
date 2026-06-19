package de.mhus.vance.shared.document;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Sticky-note attached to a {@link DocumentDocument}. Lives embedded
 * inside {@link DocumentDocument#getNotes()} as a value in a
 * {@code Map<noteId, DocumentNote>} — the map key is the canonical id
 * and is duplicated into {@link #id} for convenience when notes are
 * returned to callers as a flat list.
 *
 * <p>Notes are mutated through {@link DocumentService}'s atomic
 * {@code addNote}/{@code updateNote}/{@code deleteNote} methods, which
 * use {@code MongoTemplate} {@code $set}/{@code $unset} on the
 * {@code notes.{id}} sub-path. They never trigger a full document save
 * or an archive snapshot — annotating a document is not a content
 * change.
 *
 * <p>Coordinates ({@link #lineFrom} / {@link #lineTo}) are an optional,
 * <em>static</em> hint into the document text. They are captured when
 * the note is created and do <b>not</b> follow content edits — if the
 * underlying lines move, the note's coordinates may end up pointing at
 * unrelated text. Editors should surface this clearly in the UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNote {

    /** UUID, also the map key in {@link DocumentDocument#getNotes()}. */
    private String id;

    /** Free-form note text. May be Markdown depending on UI convention. */
    private String text;

    /** Username of the note author. */
    private String userId;

    private Instant createdAt;
    private Instant updatedAt;

    /** Workflow flag — checkbox-style done marker. */
    private boolean done;

    /**
     * Optional 1-based line number the note refers to. {@code null} means
     * the note is unanchored (file-level). Single-line only — the UI
     * surfaces notes as a gutter-dot at that line and a chip in the side
     * panel.
     *
     * <p>Static reference: the line number does <b>not</b> follow content
     * edits. If lines move around, the note may end up pointing at
     * unrelated text. Editors should make that visible to the user.
     */
    private @Nullable Integer line;

    /**
     * Display-order key. UI sorts ascending by
     * {@code (order ?? createdAtMs)} — notes without an explicit order
     * fall back to insertion order. Drag-reorder writes a midpoint
     * between the two neighbours' values, so reordering N notes never
     * needs a renumbering pass.
     */
    private @Nullable Double order;
}
