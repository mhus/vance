package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Body of {@code POST /addon/designer/reorder} — the complete design order
 * after a drag-and-drop. Stored in the manifest's
 * {@code config.designer.order}; names that are not designs are dropped
 * (lenient — the list the client sends is derived from the catalogue it
 * saw, which may be a stale view).
 */
@GenerateTypeScript("designer")
public record DesignerReorderRequest(List<String> order) {

    public DesignerReorderRequest {
        order = order == null ? List.of() : List.copyOf(order);
    }
}
