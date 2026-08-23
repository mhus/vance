package de.mhus.vance.brain.jaglan;

/**
 * Setting keys for mount configuration.
 *
 * <p><b>Two cascades, not to be confused.</b> These <i>settings</i> cascade
 * {@code _tenant} → project, like Zarniwoop's and Centauri's: a mount
 * configured in {@code _tenant} applies to <b>every</b> project of the tenant
 * (a house library is configured once), and a project overrides the same mount
 * name or switches it off for itself with {@code .enabled=false}. The
 * consequence is worth knowing — a {@code _tenant} mount shows up in
 * {@code _user_*} projects too; whoever does not want that configures per
 * project.
 *
 * <p>The <i>documents</i> do not cascade: {@code _ext} paths never appear in
 * {@code lookupCascade} or {@code listByPrefixCascade} (100-plus call sites
 * between them), which stay free of any mount awareness.
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
