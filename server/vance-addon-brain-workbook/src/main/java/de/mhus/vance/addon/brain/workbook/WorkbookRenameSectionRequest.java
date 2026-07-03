package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workbook/section/rename}.
 * Moves every page currently under {@code from} into {@code to} by
 * rewriting their storage paths. Empty {@code to} lifts the pages to
 * top level. If {@code to} already contains pages, the rename merges
 * the sections.
 */
@GenerateTypeScript("workbook")
public record WorkbookRenameSectionRequest(String from, String to) {}
