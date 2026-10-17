package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;

/** A scope the caller has, with the project behind it and whether they may write there. */
@GenerateTypeScript("contacts")
public record ContactScopeView(ContactScope scope, String projectId, boolean writable) {}
