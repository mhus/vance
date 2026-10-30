package de.mhus.vance.shared.resourceoverride;

/**
 * Which version of a type is valid, without its files — what a poll reads.
 * {@code overwrite=false} means the bundled resources apply.
 */
public record ResourceOverrideRef(String type, long version, boolean overwrite, String digest) {

    /** Compares equal for the same valid version, however the poll got it. */
    public String key() {
        return version + ":" + overwrite;
    }
}
