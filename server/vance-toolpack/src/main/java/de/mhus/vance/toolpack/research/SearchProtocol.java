package de.mhus.vance.toolpack.research;

import java.util.Set;

/**
 * A wire-format adapter. {@link SearchProtocol} is the singleton
 * Spring-bean side of the SPI: one bean per protocol implementation
 * (Serper-JSON, Wikipedia-REST, OpenAlex-REST, …).
 *
 * <p>The bean itself holds no per-endpoint state. It exposes the
 * protocol's capabilities and is asked by {@code SearchProviderFactory}
 * to produce a configured {@link SearchProviderInstance} for each
 * endpoint declared in {@code research.endpoint.<id>.*} settings.
 *
 * <p>Implementing this interface places the implementation in
 * {@code vance-toolpack} for re-use by add-ons; concrete protocols
 * usually live in {@code vance-brain/zarniwoop/protocols} or in an
 * {@code vance-addon-research-*} module.
 */
public interface SearchProtocol {

    /** Stable protocol id, kebab-case ("serper", "wikipedia"). */
    String id();

    /** Display name used in {@code research_providers} output and logs. */
    String displayName();

    /**
     * Maximum capability set the protocol can serve — concrete
     * instances may declare a subset based on configuration
     * (e.g. a Serper proxy that only handles WEB).
     */
    Set<SearchModality> modalitiesSupported();

    /**
     * Tiers the protocol can serve. NORMAL is mandatory; EXPERT is
     * advertised by protocols whose wire format supports the EXPERT
     * filter surface (site, dateRange, …).
     */
    Set<SearchTier> tiersSupported();

    /**
     * Build a configured instance of this protocol. Called once per
     * endpoint declaration during
     * {@code SearchProviderFactory.assemble(scope)}. The returned
     * instance is held in the project-scoped cache until the project
     * is suspended.
     */
    SearchProviderInstance instantiate(ProviderInstanceConfig cfg);
}
