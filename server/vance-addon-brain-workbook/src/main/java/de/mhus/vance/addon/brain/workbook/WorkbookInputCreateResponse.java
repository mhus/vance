package de.mhus.vance.addon.brain.workbook;

/** Response for {@code POST /brain/{tenant}/addon/workbook/input/create}:
 *  the path of the freshly-created text document. */
public record WorkbookInputCreateResponse(String path) {}
