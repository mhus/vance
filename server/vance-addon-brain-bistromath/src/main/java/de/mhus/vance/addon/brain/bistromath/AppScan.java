package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the client needs before it renders anything: the app's identity, the
 * views the runtime found, and where the program is.
 *
 * <p>Separate from {@link RenderedView} because the two cost differently. A
 * scan is a manifest read plus a folder listing; a view adds a document read
 * and a parse. Bundling them would make opening the app pay for every view it
 * is not showing.
 *
 * <p><b>{@code programPath} is a path, not the source.</b> The client fetches
 * the program through the ordinary document API — the same way the program
 * itself will fetch the app's data. This addon exposes no content endpoint of
 * its own: every REST route it would need already exists.
 *
 * @param folder        the app folder.
 * @param title         manifest title, or the folder's leaf name.
 * @param description   manifest description.
 * @param views         views found under the folder, ordered by path.
 * @param landingHandle the view to open, or {@code null} when there is none.
 * @param programPath   the program document, or {@code null} when the app has
 *                      none — a view-only app is valid, it just cannot act.
 * @param problems      what the scan had to refuse (unusable file names,
 *                      colliding handles, a `landing` that names nothing).
 * @param requires      the load list and what is wrong with it. The client
 *                      evaluates {@code requires.scripts} in order — the
 *                      program is the last entry, so {@code programPath} is a
 *                      convenience, not a second source of truth.
 */
@GenerateTypeScript("bistromath")
public record AppScan(
        String folder,
        String title,
        @Nullable String description,
        List<ViewRef> views,
        @Nullable String landingHandle,
        @Nullable String programPath,
        List<String> problems,
        RequireReport requires,
        /**
         * Route families the manifest declares for {@code vance.rest(...)}, or
         * {@code null} for "not declared" — which means unrestricted below the
         * floor. Nullable rather than an empty list, because "declares nothing"
         * and "does not say" are different answers and the client narrows on one
         * of them only.
         */
        @Nullable List<String> rest) {

    public AppScan {
        if (views == null) views = List.of();
        if (problems == null) problems = List.of();
    }
}
