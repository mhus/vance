package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Add and remove group members (ids or URIs) in one step. */
@GenerateTypeScript("contacts")
public record ChangeMembersRequest(@Nullable ContactScope scope, List<String> add, List<String> remove) {

    public ChangeMembersRequest {
        add = add == null ? List.of() : add;
        remove = remove == null ? List.of() : remove;
    }
}
