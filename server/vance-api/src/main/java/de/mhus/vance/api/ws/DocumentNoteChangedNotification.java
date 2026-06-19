package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.documents.DocumentNoteDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Server → Client push payload on the {@code documents} channel: a note
 * on {@link #path} was added / updated / deleted.
 *
 * <p>Last-writer-wins — the client merges the change verbatim into its
 * local notes map. {@link #note} is {@code null} on {@code "deleted"};
 * the client drops the entry keyed by {@link #noteId}.
 *
 * <p>Wire frame:
 *
 * <pre>{@code
 * { "channel": "documents",
 *   "payload": { "type": "note-changed",
 *                "data": { "path": "documents/notes.md",
 *                          "kind": "added" | "updated" | "deleted",
 *                          "noteId": "...",
 *                          "note": { ... DocumentNoteDto ... } | null } } }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class DocumentNoteChangedNotification {

    /** Document path the note belongs to. */
    private String path;

    /** {@code "added"}, {@code "updated"}, or {@code "deleted"}. */
    private String kind;

    private String noteId;

    /** Full note state on add/update; {@code null} on delete. */
    private @Nullable DocumentNoteDto note;
}
