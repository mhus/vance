package de.mhus.vance.addon.brain.workbook;

import org.jspecify.annotations.Nullable;

/** Request for {@code POST /brain/{tenant}/addon/workbook/input/save}: the
 *  full text content to persist into the bound document. */
public record WorkbookInputSaveRequest(@Nullable String content) {}
