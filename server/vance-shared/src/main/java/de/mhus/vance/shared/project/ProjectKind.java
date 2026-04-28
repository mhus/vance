package de.mhus.vance.shared.project;

/**
 * Classifies a {@link ProjectDocument} as a regular user project or a
 * system-owned project that should be hidden from default listings.
 *
 * <p>{@link #SYSTEM} is used for the per-user Vance Hub project (under the
 * {@code Home} project group) and for any future infrastructure projects that
 * must exist at the project layer but should not surface in the regular
 * project switcher. The flag drives:
 *
 * <ul>
 *   <li>UI listings — {@code SYSTEM} projects are filtered out unless the
 *       caller explicitly asks for them.</li>
 *   <li>Delete protection — {@code SYSTEM} projects refuse delete operations
 *       in {@code ProjectService}.</li>
 *   <li>Cross-project process spawning — {@code SYSTEM} hub projects host
 *       engines that may parent processes in {@code NORMAL} projects (see
 *       {@code specification/vance-engine.md} §7).</li>
 * </ul>
 */
public enum ProjectKind {

    /** Regular project owned by the user; visible in all listings. */
    NORMAL,

    /**
     * System-owned project (e.g. the per-user Vance Hub). Hidden from default
     * listings, protected from deletion, may host engines that spawn into
     * other projects.
     */
    SYSTEM
}
