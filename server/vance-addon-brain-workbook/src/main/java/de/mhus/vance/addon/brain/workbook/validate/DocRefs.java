package de.mhus.vance.addon.brain.workbook.validate;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Narrow read-only facade over the project's documents, used by the
 * validators to check references. Kept as an interface (not a direct
 * {@code DocumentService} dependency) so block validators stay pure and
 * unit-testable with an in-memory fake. Datenhoheit: the production
 * implementation delegates to {@code DocumentService} for the bound
 * tenant/project.
 */
public interface DocRefs {

    /** Whether a document exists at the project-relative {@code path}. */
    boolean exists(String path);

    /** The {@code kind} of the document at {@code path}, or {@code null}. */
    @Nullable String kindOf(String path);

    /**
     * Parse the document at {@code path} as a YAML map, or {@code null} if it
     * is missing or not a YAML mapping. Used to inspect a records data doc for
     * legacy keys.
     */
    @Nullable Map<String, Object> readYaml(String path);
}
