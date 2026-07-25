package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /addon/binder/landing} — set/clear the landing ref. */
@GenerateTypeScript("binder")
public record SetLandingRequest(@Nullable String ref) {}
