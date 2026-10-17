package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class ContactsAddonMeta implements VanceAddon {

    @Override
    public String id() {
        return "contacts";
    }

    @Override
    public String displayName() {
        return "Contacts";
    }
}
