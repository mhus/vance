package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Outcome of a vCard import. {@code skipped} entries read "name: reason". */
@GenerateTypeScript("contacts")
public record ContactImportView(
        ContactScope scope, int created, int updated, List<String> skipped, List<String> warnings) {}
