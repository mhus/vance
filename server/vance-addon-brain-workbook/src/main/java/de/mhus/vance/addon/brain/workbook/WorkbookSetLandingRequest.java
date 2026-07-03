package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workbook/landing}.
 * {@code pageId} = {@code null} unpins any current landing page; a
 * concrete id sets it. The server resolves the page's relative path
 * and writes it to the {@code workbook.landingPage} key in the
 * {@code _app.yaml} manifest.
 */
@GenerateTypeScript("workbook")
public record WorkbookSetLandingRequest(@Nullable String pageId) {}
