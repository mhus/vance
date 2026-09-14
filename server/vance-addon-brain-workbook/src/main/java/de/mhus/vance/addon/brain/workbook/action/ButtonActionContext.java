package de.mhus.vance.addon.brain.workbook.action;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Scope + inputs for one button run. {@code pagePath} is the workpage the
 * button lives on (unused by page-agnostic actions like {@code script});
 * {@code buttonConfig} is the parsed fence YAML ({@code type}, {@code title},
 * {@code script}, …).
 */
public record ButtonActionContext(
        String tenantId,
        String projectId,
        @Nullable String editorId,
        String pagePath,
        Map<String, Object> buttonConfig) {}
