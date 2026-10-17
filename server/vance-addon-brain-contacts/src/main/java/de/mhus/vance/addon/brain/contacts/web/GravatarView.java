package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Result of a Gravatar lookup: the updated card, or {@code found=false}. */
@GenerateTypeScript("contacts")
public record GravatarView(boolean found, @Nullable ContactView contact) {}
