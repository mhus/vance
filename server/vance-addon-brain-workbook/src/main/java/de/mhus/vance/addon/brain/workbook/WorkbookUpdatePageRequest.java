package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code PUT /brain/{tenant}/addon/workbook/page/{id}}.
 * Every field is optional — fields left {@code null} are not touched.
 * <ul>
 *   <li>{@code title} — rename the page (patches workpage front-matter
 *       {@code title}; the document title field follows).</li>
 *   <li>{@code section} — move the page under the named section
 *       (path-move; pass {@code ""} to lift the page to top level).</li>
 *   <li>{@code sortIndex} — set the manual sort position inside the
 *       section.</li>
 * </ul>
 */
@GenerateTypeScript("workbook")
public record WorkbookUpdatePageRequest(
        @Nullable String title,
        @Nullable String section,
        @Nullable Double sortIndex) {}
