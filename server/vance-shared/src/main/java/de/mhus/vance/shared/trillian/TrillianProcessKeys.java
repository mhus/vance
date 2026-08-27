package de.mhus.vance.shared.trillian;

/**
 * The two facts about a Trillian pair that are readable from outside the
 * engine, because they are <em>persisted</em>: the engine name that marks the
 * control half, and the {@code engineParams} key under which that half records
 * the service account its worker runs as.
 *
 * <p>Both live here rather than only in {@code TrillianSessionBootstrapper} for
 * one reason: they are the sole trace of a Trillian account outside
 * {@code vance-brain}. A Trillian mints a real user, and the project-maintenance
 * handler that has to delete it again runs in a process with no brain on its
 * classpath (the admin shell). Without these two strings there it would have to
 * guess, and a guess here leaves user corpses in the tenant.
 *
 * <p>Deliberately just the wire-level facts. Everything else about Trillian —
 * recipes, natures, the peer wiring, the lifecycle — stays in the brain, where
 * the behaviour is. The bootstrapper's constants delegate to these, so there is
 * one authority for the strings and not two that can drift.
 *
 * <p>Spec: {@code specification/public/trillian-engine.md},
 * {@code specification/public/project-maintenance.md} §7a.
 */
public final class TrillianProcessKeys {

    private TrillianProcessKeys() {}

    /**
     * Think-engine name of the human-facing half of a Trillian pair.
     *
     * <p>The only reliable discriminator between the two halves: the peer
     * wiring exists on <em>both</em> sides and points each at the other, so
     * keying on it makes any cascade run back and forth.
     */
    public static final String CONTROL_ENGINE_NAME = "trillian-control";

    /**
     * {@code engineParams} key holding the service-account username the pair
     * runs its worker loop as (a {@code _}-prefixed service account, e.g.
     * {@code _trillian-void-a7f3}).
     */
    public static final String PARAM_TRILLIAN_USER_NAME = "trillianUserName";
}
