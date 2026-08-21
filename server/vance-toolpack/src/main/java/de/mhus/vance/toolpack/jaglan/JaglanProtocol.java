package de.mhus.vance.toolpack.jaglan;

/**
 * A wire-format adapter for a family of mount sources. One Spring bean per
 * protocol: {@code ode} (the contract vance-ode defines for any foreign
 * software that wants to expose files), later others.
 *
 * <p>The bean holds no per-mount state. It is asked by the source factory to
 * produce a configured {@link JaglanInstance} for each mount declared in
 * {@code jaglan.mount.<name>.*} settings.
 *
 * <p>As with {@code FeedProtocol}, the protocol id names the <b>transport</b>
 * and not the first system to speak it: {@code ode}, not the name of the
 * library that happens to be behind it.
 */
public interface JaglanProtocol {

    /** Stable protocol id, kebab-case ("ode", "local"). */
    String id();

    /** Display name for configuration UI and logs. */
    String displayName();

    /**
     * Build a configured instance. Called once per mount declaration while
     * the factory assembles a project's mounts; the result is held in the
     * project-scoped cache until the project is suspended.
     *
     * <p>May throw to refuse an unusable configuration — the factory logs it
     * and keeps the other mounts running. A refused mount is not reported as
     * a source, so it does not appear as a dead folder in the tree.
     */
    JaglanInstance instantiate(JaglanInstanceConfig cfg);
}
