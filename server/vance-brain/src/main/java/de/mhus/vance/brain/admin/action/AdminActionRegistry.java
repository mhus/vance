package de.mhus.vance.brain.admin.action;

import de.mhus.vance.api.admin.AdminActionDto;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * All admin actions on this brain — every {@link AdminAction} Spring
 * bean, collected once at boot. Addons contribute actions by
 * registering beans; nothing here knows a concrete action, and no
 * action needs an entry in this file.
 *
 * <p>Duplicate ids are a boot failure rather than a shadowing bug: two
 * actions claiming the same id would make the run path ambiguous, and
 * the registry is the one place that can see the collision.
 */
@Component
public class AdminActionRegistry {

    private final Map<String, AdminAction> actionsById;

    public AdminActionRegistry(List<AdminAction> actions) {
        Map<String, AdminAction> byId = new LinkedHashMap<>();
        for (AdminAction action : actions) {
            AdminAction previous = byId.put(action.id(), action);
            if (previous != null) {
                throw new IllegalStateException("Duplicate AdminAction id '" + action.id() + "' — "
                        + action.getClass().getName() + " vs "
                        + previous.getClass().getName());
            }
        }
        this.actionsById = byId;
    }

    /** All actions, ordered by id — the listing surface's stable order. */
    public List<AdminActionDto> list() {
        return actionsById.values().stream()
                .sorted(Comparator.comparing(AdminAction::id))
                .map(AdminActionRegistry::descriptorOf)
                .toList();
    }

    /** The action with the given id, or empty when no bean registered it. */
    public java.util.Optional<AdminAction> find(String id) {
        return java.util.Optional.ofNullable(actionsById.get(id));
    }

    private static AdminActionDto descriptorOf(AdminAction action) {
        return AdminActionDto.builder()
                .id(action.id())
                .title(action.title())
                .description(action.description())
                .scope(action.scope().name())
                .build();
    }
}
