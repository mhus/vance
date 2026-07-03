package de.mhus.vance.addon.brain.workbook;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /brain/{tenant}/addon/workbook/form}: the current
 * {@code items} records of the data document. The form definition lives in
 * the block's fence, not here.
 */
public record WorkbookFormResponse(
        List<Map<String, Object>> records) {}
