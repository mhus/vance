package de.mhus.vance.brain.resources;

/**
 * A component whose bundled resources can be overridden centrally. It loads its
 * file list through {@link BundledResourceResolver#load} and swaps its own
 * snapshot atomically in {@link #reload()}, which {@link ResourceOverrideWatcher}
 * calls when the valid version of the section changed.
 */
public interface ResourceOverrideConsumer {

    /** The section this consumer reads, see {@code ResourceOverrideSections}. */
    String section();

    /** Rebuild from {@link BundledResourceResolver#load} and publish the result. */
    void reload();
}
