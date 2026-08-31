package de.mhus.vance.brain.access;

import java.util.List;

/**
 * A named set of REST surfaces an
 * {@link de.mhus.vance.shared.jwt.TokenType#INTEGRATION} token may touch.
 *
 * <p>Declared as a bean by whoever owns the surface — the links addon declares
 * what a link-capture integration needs, not this package. Same shape as every
 * other extension seam in the tree ({@code ShareHandler}, {@code RunSource},
 * {@code DamogranTask}): the thing that has the capability describes it.
 *
 * <p><b>Why the token carries the profile's name and not its contents.</b>
 * These tokens are long-lived by definition. A path list copied into a token at
 * mint time is a permission decision frozen on that day, while the URLs keep
 * moving — split one endpoint into two and the year-old token silently grants
 * the wrong set, with nothing to notice it. A name is resolved against the
 * running code on every request, so a surface that gets renamed or split takes
 * its profile with it. The cost is that a token cannot be read on its own; the
 * profile registry answers that instead, and it answers with today's truth.
 *
 * <p>A profile is a <em>ceiling</em>, never a grant: what the token may
 * actually do is the intersection of this list, the project pin, and the
 * account's own permission grants.
 */
public interface IntegrationScopeProfile {

    /**
     * Stable id, referenced by the {@code scp} claim. Kebab-case, scoped to
     * what it is for: {@code links-capture}, not {@code links}.
     *
     * <p>Renaming one invalidates every token that names it — the tokens do
     * not break dangerously, they stop working, which is the safe direction.
     */
    String id();

    /** Human label for the mint form and the token list. */
    String label();

    /**
     * The surfaces this profile opens. Paths are relative to the tenant root,
     * i.e. what follows {@code /brain/{tenant}} — the tenant is already pinned
     * by the {@code tid} claim, so repeating it here would be a second place to
     * get it wrong.
     */
    List<IntegrationSurface> surfaces();

    /**
     * Whether a token of this profile must name a project.
     *
     * <p>Default {@code true}, and that default is the point: nearly every
     * integration works inside one project, and a token that forgot to say
     * which one would reach all of them. A profile that genuinely spans
     * projects has to say so out loud.
     */
    default boolean requiresProject() {
        return true;
    }
}
