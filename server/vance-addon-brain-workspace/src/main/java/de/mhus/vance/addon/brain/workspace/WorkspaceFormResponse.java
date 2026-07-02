package de.mhus.vance.addon.brain.workspace;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /brain/{tenant}/addon/workspace/form}: the current
 * {@code items} records of the data document. The form definition lives in
 * the block's fence, not here.
 */
public record WorkspaceFormResponse(
        List<Map<String, Object>> records) {}
