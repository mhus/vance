package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** The pin ids in their new order. Pins left out keep their place at the end. */
@GenerateTypeScript("contacts")
public record ReorderPinsRequest(List<String> order) {

    public ReorderPinsRequest {
        order = order == null ? List.of() : order;
    }
}
