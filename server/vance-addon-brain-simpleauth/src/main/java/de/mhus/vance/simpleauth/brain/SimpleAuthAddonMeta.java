package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/**
 * Marker bean for the Insights addons tab. Without this, simpleauth is
 * invisible in the Brain's addon list whenever there is no db.addons row
 * for it (IDE launch / before the .vab has been staged) — the list is the
 * union of db rows and {@link VanceAddon} beans. Picked up by
 * {@link BrainSimpleAuthAddon}'s component scan.
 */
@Component
public class SimpleAuthAddonMeta implements VanceAddon {

    @Override public String id() { return "simpleauth"; }

    @Override public String displayName() { return "Permissions"; }
}
