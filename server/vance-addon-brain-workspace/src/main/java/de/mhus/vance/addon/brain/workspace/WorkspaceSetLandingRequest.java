package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/landing}.
 * {@code pageId} = {@code null} unpins any current landing page; a
 * concrete id sets it. The server resolves the page's relative path
 * and writes it to the {@code workspace.landingPage} key in the
 * {@code _app.yaml} manifest.
 */
@GenerateTypeScript("workspace")
public record WorkspaceSetLandingRequest(@Nullable String pageId) {}
