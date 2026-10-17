package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One group member as shown: the stored value, and the card it resolved to
 * ({@code contact} is {@code null} for a URI member or a dangling id).
 */
@GenerateTypeScript("contacts")
public record ContactMemberView(String member, @Nullable ContactSummaryView contact) {}
