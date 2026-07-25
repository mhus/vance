package de.mhus.vance.simpleauth.anus;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/**
 * Marker bean for the anus live-addon view ({@code addon active}). Without it,
 * anus-simpleauth is invisible to any live-loaded listing whenever there is no
 * db.addons row (IDE launch / before the .vab is staged) even though its
 * {@code permission grant *} commands are registered. Picked up by
 * {@link AnusSimpleAuthAddon}'s component scan.
 */
@Component
public class AnusSimpleAuthAddonMeta implements VanceAddon {

    @Override public String id() { return "anus-simpleauth"; }

    @Override public String displayName() { return "Permissions"; }
}
