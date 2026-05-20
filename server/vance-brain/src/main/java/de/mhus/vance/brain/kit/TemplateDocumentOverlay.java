package de.mhus.vance.brain.kit;

import java.util.List;

/**
 * One entry in the {@code documents:} filter overlay of a
 * {@link TemplateDescriptor}. When present, the applier drops the
 * referenced kit document from the build tree unless one of
 * {@link #requires} is in the user's multi-select selection.
 *
 * <p>YAML schema:
 * <pre>
 *   documents:
 *     - path: server-tools/jira_rest.yaml
 *       requires: jira                    # single feature
 *     - path: server-tools/atlassian_admin.yaml
 *       requires: [jira, confluence]      # any-of
 * </pre>
 *
 * <p>Documents <strong>not</strong> listed here are installed
 * unconditionally — the overlay is an opt-in filter, not an exhaustive
 * whitelist. Paths are relative to {@code <kit>/documents/}.
 *
 * @param path     document path relative to {@code documents/} —
 *                 e.g. {@code server-tools/jira_rest.yaml}
 * @param requires choice values (from the template's multi-select
 *                 input) that, if at least one is selected, cause the
 *                 document to be installed
 */
public record TemplateDocumentOverlay(
        String path,
        List<String> requires) {

    public TemplateDocumentOverlay {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("documents entry: 'path' is required");
        }
        if (path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException(
                    "documents entry '" + path + "': must be a relative path inside documents/");
        }
        requires = requires == null ? List.of() : List.copyOf(requires);
        if (requires.isEmpty()) {
            throw new IllegalArgumentException(
                    "documents entry '" + path + "': 'requires' must list at least one feature value "
                            + "(omit the entry entirely to always install the document)");
        }
    }
}
