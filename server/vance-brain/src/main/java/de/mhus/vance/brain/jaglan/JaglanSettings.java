package de.mhus.vance.brain.jaglan;

/**
 * Setting keys for mount configuration.
 *
 * <p>Project-scoped and deliberately <b>not</b> cascading to {@code _tenant}:
 * a mount makes foreign content appear under a project path, and the decision
 * which project sees which source belongs to that project alone. It also keeps
 * {@code lookupCascade} and {@code listByPrefixCascade} — 100-plus call sites
 * between them — free of any mount awareness.
 */
public final class JaglanSettings {

    private JaglanSettings() {}

    /** {@code jaglan.mount.<name>.<field>} */
    public static final String PREFIX_MOUNT = "jaglan.mount.";

    /** Which {@code JaglanProtocol} serves this mount. Required — a mount
     *  without it is skipped, which is the hook a setting form uses to
     *  disable one without deleting the other keys. */
    public static final String SUFFIX_PROTOCOL = ".protocol";

    /** Endpoint for protocols that speak to a remote; empty for local ones. */
    public static final String SUFFIX_BASE_URL = ".baseUrl";

    /** Credential, stored encrypted and read through the secret cascade. */
    public static final String SUFFIX_API_KEY = ".apiKey";

    /** {@code false} keeps the mount configured but out of the tree. */
    public static final String SUFFIX_ENABLED = ".enabled";

    public static String mountProtocol(String mount) {
        return PREFIX_MOUNT + mount + SUFFIX_PROTOCOL;
    }

    public static String mountBaseUrl(String mount) {
        return PREFIX_MOUNT + mount + SUFFIX_BASE_URL;
    }

    public static String mountApiKey(String mount) {
        return PREFIX_MOUNT + mount + SUFFIX_API_KEY;
    }

    public static String mountEnabled(String mount) {
        return PREFIX_MOUNT + mount + SUFFIX_ENABLED;
    }

    /** The four keys the factory itself consumes; everything else on a mount
     *  is handed to the protocol as {@code extras}. */
    static boolean isCommonField(String fieldSuffix) {
        return fieldSuffix.equals(bare(SUFFIX_PROTOCOL))
                || fieldSuffix.equals(bare(SUFFIX_BASE_URL))
                || fieldSuffix.equals(bare(SUFFIX_API_KEY))
                || fieldSuffix.equals(bare(SUFFIX_ENABLED));
    }

    static String bare(String withLeadingDot) {
        return withLeadingDot.substring(1);
    }
}
