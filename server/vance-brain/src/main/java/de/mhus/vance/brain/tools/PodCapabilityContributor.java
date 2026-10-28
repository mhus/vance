package de.mhus.vance.brain.tools;

import java.util.Set;

/**
 * A component that offers host capabilities ({@code ToolCapabilities}) when what it
 * needs is present on this pod — a program in the image, later a connected runner.
 * {@link PodCapabilities} asks every contributor once at startup.
 */
public interface PodCapabilityContributor {

    /** The capabilities this component offers here; empty when it cannot run. */
    Set<String> capabilities();
}
