package de.mhus.vance.shared.access;

import de.mhus.vance.api.ws.Profiles;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves a connection-profile string into its
 * {@link ProfileCapabilities} bundle. Single source of truth for the
 * profile-vs-capability mapping documented in
 * {@code specification/engine-message-routing.md} §4.1.1.
 *
 * <p>Unknown profiles fall back to
 * {@link ProfileCapabilities#RESTRICTIVE} — tenants can ship
 * custom profiles without code changes; the default position is
 * "no capabilities" until somebody opts them in. Same idea as the
 * recipe-block fallback to {@code default}.
 */
@Component
public class ProfileRegistry {

    /**
     * Returns the capability bundle for the given profile string.
     * {@code null} or unknown values resolve to
     * {@link ProfileCapabilities#RESTRICTIVE}.
     */
    public ProfileCapabilities capabilities(@Nullable String profile) {
        if (profile == null || profile.isBlank()) {
            return ProfileCapabilities.RESTRICTIVE;
        }
        return switch (profile) {
            case Profiles.FOOT -> new ProfileCapabilities(
                    /*clientToolsEnabled=*/ true,
                    /*canMediate=*/ true,
                    /*forwardingTagsExpected=*/ false);
            case Profiles.WEB -> new ProfileCapabilities(
                    /*clientToolsEnabled=*/ false,
                    /*canMediate=*/ true,
                    /*forwardingTagsExpected=*/ false);
            case Profiles.MOBILE -> new ProfileCapabilities(
                    /*clientToolsEnabled=*/ true,
                    /*canMediate=*/ false,
                    /*forwardingTagsExpected=*/ false);
            case Profiles.EDDIE -> new ProfileCapabilities(
                    /*clientToolsEnabled=*/ false,
                    /*canMediate=*/ false,
                    /*forwardingTagsExpected=*/ true);
            case Profiles.DAEMON -> ProfileCapabilities.RESTRICTIVE;
            default -> ProfileCapabilities.RESTRICTIVE;
        };
    }
}
