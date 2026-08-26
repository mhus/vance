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
        @Nullable List<String> restFamilies,
        /**
         * Whether the app may have a **visible drawing surface** (`region:`).
         *
         * <p>Not about raw DOM — the guest has its own document either way,
         * `region:` only makes it visible and gives it a height. The risk is
         * what it can paint: arbitrary pixels that look like Vance. A convincing
         * "your session expired, enter your password" inside the page is the
         * sharpest reader-facing thing in this runtime, and switching that off
         * is exactly what a policy about protecting the reader from the app is
         * for.
         *
         * <p>Default under {@code restricted} is **false**: an admin who wants
         * an app to paint its own pixels can say so, and the restrictive reading
         * is the right one for the single lever with a phishing shape.
         */
        boolean surface,
        /**
         * Whether the app may **write** documents ({@code vance.documents.write}
         * / {@code create} / {@code delete}).
         *
         * <p>The lever that was missing from the plan: {@code restricted}
         * narrows REST, but documents are a separate host surface, so a
         * restricted app could still delete its own folder. "May show, may not
         * change" is the distinction an admin expects — and it is the borrowed
         * click again: the reader could delete it themselves, but did not want
         * to; the app did it in their name.
         *
         * <p>Default under {@code restricted} is **true**, unlike
         * {@link #surface}. Taking an app's own data away by default would make
         * a bare {@code restricted} mean "broken" for every register-shaped app,
         * and the admin would have no idea why.
         */
        boolean documentsWritable) {


    public AppPolicy {
        if (restFamilies != null) restFamilies = List.copyOf(restFamilies);
    }

    public static AppPolicy allowed() {
        return new AppPolicy(AppMode.ALLOWED, null, true, true);
    }

    public static AppPolicy forbidden() {
        // Nothing runs, so the capability flags are moot — false rather than
        // true, so a caller reading them without checking the mode first errs
        // on the closed side.
        return new AppPolicy(AppMode.FORBIDDEN, null, false, false);
    }

    public boolean forbids() {
        return mode == AppMode.FORBIDDEN;
    }
}
