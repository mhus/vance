package de.mhus.vance.toolpack.feed;

/**
 * A wire-format adapter for a family of feed sources. One Spring bean per
 * protocol: {@code ode} (the contract vance-ode defines for any foreign
 * software that wants to be a source), {@code mastodon}, later others.
 *
 * <p>The bean holds no per-endpoint state. It is asked by
 * {@code FeedSourceFactory} to produce a configured
 * {@link FeedSourceInstance} for each endpoint declared in
 * {@code centauri.endpoint.<id>.*} settings.
 *
 * <p>The protocol id is <b>{@code ode}, not {@code hrafnagud}</b>. The
 * contract belongs to vance-ode; Hrafnagud is its first implementation, not
 * its measure. An SPI with a single consumer is only that consumer's API
 * with extra steps, which is why a second, genuinely foreign protocol
 * (Mastodon) exists from the start.
 */
public interface FeedProtocol {

    /** Stable protocol id, kebab-case ("ode", "mastodon"). */
    String id();

    /** Display name for configuration UI and logs. */
    String displayName();

    /**
     * Build a configured instance. Called once per endpoint declaration
     * while the factory assembles a project's sources; the result is held
     * in the project-scoped cache until the project is suspended.
     *
     * <p>May throw to refuse an unusable configuration — the factory logs
     * it and keeps the other endpoints running.
     */
    FeedSourceInstance instantiate(FeedInstanceConfig cfg);
}
