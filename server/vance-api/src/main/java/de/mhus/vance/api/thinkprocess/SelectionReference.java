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
 * What a message pointed at — the durable half of an app selection.
 *
 * <p>{@link ActiveAppContext#getSelection()} answers "where is the reader
 * looking <em>now</em>" and is deliberately thrown away after the turn:
 * carrying it forward would tell the model the reader is in a folder they
 * left minutes ago. But a message that says "tell me more about the
 * selected case" does not describe a moment — it makes a <b>reference</b>,
 * and a reference belongs to the sentence that made it. Persisted on the
 * USER chat message, it keeps the sentence from becoming a pronoun without
 * an antecedent once the entry has scrolled out of the feed.
 *
 * <p><b>Label plus at least one address.</b> The label is the <em>name</em>
 * of the thing, not the thing — a reference that is only a label cannot be
 * followed, so one of {@link #vanceUri} / {@link #url} must be present
 * (same invariant Milliways puts on {@code ShareSubject}). Both are worth
 * having where both exist, because they fail independently:
 *
 * <ul>
 *   <li>{@link #vanceUri} addresses the entry <em>in this installation</em>
 *       ({@code vance:/<folder>/_app.yaml?entry=<handle>}, the Inter-Links
 *       grammar) and is what the app's own tool re-reads — the fassung the
 *       reader actually saw. Dies when the source can no longer serve the
 *       item.</li>
 *   <li>{@link #url} addresses the thing at its origin and is what
 *       {@code web_fetch} reads. Dies when the publisher moves it behind a
 *       paywall or deletes it — but survives our stream window.</li>
 * </ul>
 *
 * <p>Not every selectable thing has both: a search hit has no handle on
 * this side (a search is stateless — its address is what it is), and a
 * document-shaped entry may have no foreign URL at all. The label is
 * the last line of defence: when both addresses have died, the sentence
 * still says what it was about.
 *
 * <p><b>No body.</b> Content is re-fetchable through either address and is
 * foreign text; copying it into the reference would put an unbounded
 * excerpt into every later prompt.
 *
 * <p>Declared by the app that owns the selection — the addon that renders
 * the card is the only place that knows what a click on it means. The
 * brain hardens what arrives (collapse, cap, scheme allow-list) before it
 * is persisted or rendered.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class SelectionReference {

    /**
     * Human-readable name of the referenced thing — a headline, a hit
     * title, a card label. Required: without it a dead address is an
     * unreadable reference.
     */
    @NotBlank
    private String label;

    /**
     * In-installation address, {@code vance:/<folder>/_app.yaml?entry=<handle>}
     * — see {@code specification/public/inter-links.md}. The handle is
     * app-owned and opaque; for a feed entry it is {@code <sourceId>/<itemId>},
     * which is also what the app's own read-tool takes. {@code null} when the
     * app has no stable handle for the selection.
     */
    @Nullable
    private String vanceUri;

    /**
     * Foreign address of the referenced thing (the article, the hit).
     * {@code null} when the selection has no life outside this
     * installation. Restricted to {@code http}/{@code https} on ingest.
     */
    @Nullable
    private String url;
}
