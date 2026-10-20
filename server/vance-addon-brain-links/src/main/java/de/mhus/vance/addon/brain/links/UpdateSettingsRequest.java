package de.mhus.vance.addon.brain.links;

/**
 * Body of {@code POST /addon/links/settings}. Sets the list default for how a
 * link opens; a blank {@code open} clears the setting, which reads the same as
 * the built-in default ({@code tab}) but does not claim the manifest chose it.
 *
 * <p>No {@code @GenerateTypeScript}: like {@code SetViewedRequest}, this body
 * is a single typed field the client sends inline — a generated interface
 * would be ceremony around one string.
 */
public record UpdateSettingsRequest(String open) {}
