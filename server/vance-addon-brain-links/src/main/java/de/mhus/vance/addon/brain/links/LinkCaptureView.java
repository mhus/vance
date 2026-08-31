package de.mhus.vance.addon.brain.links;

import org.jspecify.annotations.Nullable;

/**
 * Answer of {@code POST /addon/links/capture} — what happened to one link.
 *
 * <p><b>Why this exists next to {@code POST /entry}.</b> That route answers with
 * the whole {@link LinksView}, which is right for the app: a mutation can move
 * an entry, so the order afterwards is the server's to state. A capture tool has
 * no list to reorder. It wants a few hundred bytes back on every click, and it
 * wants the one fact the app route throws away — {@link #added}, which is the
 * difference between "saved" and "you already have this".
 *
 * <p>Not folded into {@code /entry} behind a query parameter: a route whose
 * response type depends on a flag cannot be typed on either side.
 *
 * <p>No {@code @GenerateTypeScript} — this shape serves an external HTTP client
 * (a browser extension, a shell alias), not the Vue app, which goes through
 * {@code /entry} and gets the full view.
 *
 * @param added  false when the URL was already in the list. Nothing was
 *               changed in that case — the fields below describe the row that
 *               was already there, so the caller can say where it sits.
 * @param url    the stored, normalised form. Worth returning even on the happy
 *               path: the caller sent whatever the address bar held.
 * @param title  what is stored — fetched from the page when none was given.
 * @param group  where it sits, {@code null} for the ungrouped lead section.
 * @param viewed whether the reader has already marked it seen. Only ever true
 *               on an {@code added == false} answer.
 */
public record LinkCaptureView(
        boolean added,
        String url,
        @Nullable String title,
        @Nullable String group,
        boolean viewed) {

    public static LinkCaptureView of(LinksManifestOps.CaptureResult result) {
        LinkEntry e = result.entry();
        return new LinkCaptureView(result.added(), e.url(), e.title(), e.group(), e.viewed());
    }
}
