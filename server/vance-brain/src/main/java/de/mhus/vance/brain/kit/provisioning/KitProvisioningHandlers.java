package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.shared.kit.KitException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Registry of provisioning mechanisms, keyed by {@link
 * KitProvisioningHandler#id()}.
 *
 * <p>The single place that turns a {@code type:} in a project's
 * provisioning document into a mechanism. Nothing else looks handlers up,
 * so nothing else has to know the set is open.
 */
@Service
@Slf4j
public class KitProvisioningHandlers {

    private final Map<String, KitProvisioningHandler> byId;

    public KitProvisioningHandlers(List<KitProvisioningHandler> handlers) {
        Map<String, KitProvisioningHandler> map = new LinkedHashMap<>();
        for (KitProvisioningHandler handler : handlers) {
            String id = handler.id();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException(
                        handler.getClass().getName() + " has no provisioning id");
            }
            KitProvisioningHandler clash = map.put(id, handler);
            if (clash != null) {
                // Refused at boot rather than resolved by whichever bean the
                // context happened to order last: two mechanisms under one id
                // means one of them is silently never used, and the symptom
                // would be a project provisioning from the wrong place.
                throw new IllegalStateException("two kit provisioning handlers both claim id '"
                        + id + "': " + clash.getClass().getName() + " and "
                        + handler.getClass().getName());
            }
        }
        this.byId = Map.copyOf(map);
        log.info("Kit provisioning mechanisms: {}", byId.keySet());
    }

    /** Ids that are available in this build — for messages and diagnostics. */
    public Set<String> ids() {
        return byId.keySet();
    }

    /**
     * The desired-list one entry produces.
     *
     * <p>An unknown {@code type:} is a {@link KitException} naming what
     * this build does have. A hand-written document with a typo is the
     * likely cause, and a silent skip would look like the source having
     * nothing to offer.
     */
    public List<DesiredKit> discover(String tenantId, String projectId,
            KitProvisioningEntry entry) {
        KitProvisioningHandler handler = byId.get(entry.type());
        if (handler == null) {
            throw new KitException("no kit provisioning mechanism '" + entry.type()
                    + "' in this Vancetope build — available: " + byId.keySet());
        }
        return handler.discover(new KitProvisioningContext(tenantId, projectId, entry));
    }
}
