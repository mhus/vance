package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workbook/reorder}.
 * The server assigns {@code sortIndex} {@code 10, 20, 30, …} to the
 * given page ids in order. Pages not in the list are not touched; this
 * is intentionally a partial-write so a cross-section move can stage
 * the path-update first and then call reorder for the target section
 * only.
 */
@GenerateTypeScript("workbook")
public record WorkbookReorderRequest(List<String> orderedIds) {}
