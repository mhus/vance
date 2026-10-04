package de.mhus.vance.brain.ai;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Pattern match for model allowlists ({@code *.allowed-models} settings):
 * comma-separated patterns with {@code *} wildcards, matched
 * case-insensitively against the full {@code providerInstance:modelName}
 * and the bare {@code modelName} alike — {@code sipgate-coding-pro} and
 * {@code coding-proxy:*} both work.
 *
 * <p><b>Blank means "nothing approved"</b> here, which is the Wowbagger
 * (fail-closed) policy. Callers with a relaxed policy substitute their own
 * default before calling (Trillian treats an unset setting as {@code *}),
 * so the two policies differ in what the caller passes, not in what this
 * matcher means.
 */
public final class ModelAllowlist {

    private ModelAllowlist() {}

    /**
     * Whether {@code resolvedModel} matches one of the comma-separated
     * patterns. A {@code null}/blank allowlist approves nothing.
     */
    public static boolean approved(String resolvedModel, @Nullable String allowlistCsv) {
        if (allowlistCsv == null || allowlistCsv.isBlank()) {
            return false;
        }
        int colon = resolvedModel.indexOf(':');
        String bare = colon >= 0 ? resolvedModel.substring(colon + 1) : resolvedModel;
        for (String raw : allowlistCsv.split(",")) {
            String pattern = raw.trim().toLowerCase();
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.equals("*")) {
                return true;
            }
            Pattern regex = Pattern.compile("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E");
            if (regex.matcher(resolvedModel.toLowerCase()).matches()
                    || regex.matcher(bare.toLowerCase()).matches()) {
                return true;
            }
        }
        return false;
    }
}
