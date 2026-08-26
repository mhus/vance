package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How this tenant wants custom applications handled — the answer for **one**
 * app, already resolved.
 *
 * <p><b>What it is.</b> Protection of the *reader* from an app: Alice writes it,
 * Bob opens it, this limits what Alice's code does in Bob's browser. Plus the
 * operator switch to keep the feature out of a tenant entirely.
 *
 * <p><b>What it is not.</b> It does not restrain the app's *author*. Alice may
 * call anything she may call as Alice — from the console, from her own script,
 * with or without this policy. Restraining her is the permission system's job,
 * and the mechanisms for it exist ({@code web: true} on a recipe, the floor in
 * {@code restPolicy.ts}). An admin writing {@code restricted} and expecting the
 * second thing is being misled by the word, which is why this sentence is also
 * in the config document and in the manual.
 *
 * <p><b>Why the client enforces it.</b> The guest can issue HTTP but never
 * <em>authenticated</em> HTTP to us — opaque origin, no cookie. So every call
 * that arrives went through {@code vance.rest}, i.e. through the host, and a
 * check there is complete for the app's code. A server-side filter was
 * considered and dropped: the only thing it could catch is a caller who
 * voluntarily identifies as an app, and one who omits that identity is simply
 * the user with the rights they already had. Same protected set, more
 * machinery. See {@code planning/app-governance.md}.
 *
 * <p>The <b>resolution</b> is the server's, though — see
 * {@link ApplicationsConfig}. Not for trust: so the tenant's rule set does not
 * end up in every browser, readable by every project member.
 */
@GenerateTypeScript("bistromath")
public record AppPolicy(
        AppMode mode,
        /**
         * REST route families this app may call, or {@code null} for "no
         * restriction beyond the floor".
         *
         * <p>Only meaningful with {@link AppMode#RESTRICTED}. An **empty list** is
         * a decision — no REST at all — and is what {@code restricted} means
         * when the admin named no families: inventing a set would be guessing
         * at what they meant.
         *
         * <p>The two expensive levers need no fields of their own:
         * {@code light-llm} and {@code processes} <em>are</em> route families,
         * so leaving them out of this list is how they are switched off.
         *
         * <p>It does **not** cover {@code vance.documents.*} — that is a
         * separate host surface, app-folder-scoped, and it stays available. An
         * app restricted to no REST can still show and edit its own documents,
         * which is what makes {@code restricted} usable rather than a synonym
         * for {@code forbidden}.
         */
        @Nullable List<String> restFamilies) {


    public AppPolicy {
        if (restFamilies != null) restFamilies = List.copyOf(restFamilies);
    }

    public static AppPolicy allowed() {
        return new AppPolicy(AppMode.ALLOWED, null);
    }

    public static AppPolicy forbidden() {
        return new AppPolicy(AppMode.FORBIDDEN, null);
    }

    public boolean forbids() {
        return mode == AppMode.FORBIDDEN;
    }
}
