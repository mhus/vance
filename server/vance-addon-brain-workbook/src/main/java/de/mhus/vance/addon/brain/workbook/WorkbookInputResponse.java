package de.mhus.vance.addon.brain.workbook;

/** Response for {@code GET /brain/{tenant}/addon/workbook/input}: the
 *  editable text body of the bound document (front-matter header stripped). */
public record WorkbookInputResponse(String content) {}
