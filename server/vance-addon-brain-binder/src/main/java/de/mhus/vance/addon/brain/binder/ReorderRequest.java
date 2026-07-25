package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Body of {@code POST /addon/binder/reorder} — the new entry order (by ref). */
@GenerateTypeScript("binder")
public record ReorderRequest(List<String> orderedRefs) {}
