package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Groups that still list the deleted card — they show it as "not found" now. */
@GenerateTypeScript("contacts")
public record DeleteContactView(String id, List<ContactSummaryView> groups) {}
