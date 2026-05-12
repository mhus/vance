package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Which surface the session-search query targets.
 *
 * <ul>
 *   <li>{@link #METADATA} — only the session's own title and tags
 *       (Mongo text index on {@code SessionDocument}). Fast.</li>
 *   <li>{@link #CONTENT} — only the chat history
 *       ({@code ChatMessageDocument.content}, Mongo text index).
 *       Slower; results carry a match snippet.</li>
 *   <li>{@link #BOTH} — runs both and merges. Default for the
 *       generic search bar.</li>
 * </ul>
 */
@GenerateTypeScript("session")
public enum SessionSearchScope {
    METADATA,
    CONTENT,
    BOTH
}
