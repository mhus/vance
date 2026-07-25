package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Response of the binder document-picker search. */
@GenerateTypeScript("binder")
public record BinderDocSearchResponse(List<BinderDocItem> items, long total) {}
