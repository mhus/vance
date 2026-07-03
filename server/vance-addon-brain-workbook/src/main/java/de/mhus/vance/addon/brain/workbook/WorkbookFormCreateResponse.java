package de.mhus.vance.addon.brain.workbook;

/**
 * Response for {@code POST /brain/{tenant}/addon/workbook/form/create}:
 * the path of the freshly-created form data document. The client turns
 * it into a {@code vance:} URI and inserts a {@code vance-form} block.
 */
public record WorkbookFormCreateResponse(
        String configPath) {}
