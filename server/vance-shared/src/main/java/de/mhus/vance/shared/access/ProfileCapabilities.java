package de.mhus.vance.shared.access;

/**
 * Capability bundle resolved per connection-profile, see
 * {@code specification/engine-message-routing.md} §4.1.1. Each
 * canonical profile (foot, web, mobile, eddie, daemon) maps to one
 * of these via {@link ProfileRegistry}.
 *
 * <ul>
 *   <li>{@link #clientToolsEnabled} — whether server-side tool
 *       resolution adds the {@code client_*} family. Foot + mobile
 *       have local FS / exec; web has a browser, no local FS;
 *       eddie can't route client-tool results back to a specific
 *       user-WS, so they're disabled there too.</li>
 *   <li>{@link #canMediate} — whether the connected client has a
 *       trigger to come back from a mediated worker session
 *       ({@code /hub} slash command on foot, equivalent UI button
 *       on web). Mobile is voice-only, no trigger surface, so
 *       Eddie skips MEDIATE for mobile clients to avoid stranding
 *       them on a worker session.</li>
 *   <li>{@link #forwardingTagsExpected} — whether server frames
 *       carry the {@code forwardedBy} envelope tag (eddie-as-client).</li>
 * </ul>
 */
public record ProfileCapabilities(
        boolean clientToolsEnabled,
        boolean canMediate,
        boolean forwardingTagsExpected) {

    /** Most restrictive default — used for unknown / future profiles. */
    public static final ProfileCapabilities RESTRICTIVE = new ProfileCapabilities(
            /*clientToolsEnabled=*/ false,
            /*canMediate=*/ false,
            /*forwardingTagsExpected=*/ false);
}
