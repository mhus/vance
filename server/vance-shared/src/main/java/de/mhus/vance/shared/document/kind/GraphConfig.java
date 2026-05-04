package de.mhus.vance.shared.document.kind;

/**
 * Document-level graph options. Currently only {@code directed}
 * (default {@code true}); other knobs may follow as the spec evolves.
 */
public record GraphConfig(boolean directed) {

    public static GraphConfig defaults() {
        return new GraphConfig(true);
    }
}
