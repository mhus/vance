package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client → Brain payload for {@code documents}-channel
 * {@code subscribePrefix} and {@code unsubscribePrefix} frames.
 *
 * <p>Carries a folder prefix the client wants to be informed about for
 * every document write underneath. Used by folder-bound apps (Calendar,
 * Kanban, Slideshow, …) so a single subscription covers the manifest
 * plus every sub-document, without enumerating individual paths.
 *
 * <p>The prefix MUST end with a {@code /} so {@code foo/} never matches
 * {@code foobar/baz}. The server rejects prefixes shorter than two
 * characters (a single {@code /} would subscribe to everything under
 * root, which is never the intent).
 *
 * <pre>{@code
 * { "channel": "documents",
 *   "payload": { "type": "subscribePrefix",
 *                "data": { "prefix": "calendars/q3/" } } }
 * }</pre>
 *
 * <p>See {@code planning/apps-in-cortex-and-live.md} §6.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class DocumentPrefixSubscribeRequest {

    /** Folder prefix, MUST end with {@code /}. Required. */
    private String prefix;
}
