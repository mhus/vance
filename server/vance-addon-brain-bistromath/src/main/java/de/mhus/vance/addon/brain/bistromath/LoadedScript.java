package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One document in an app's load list, in the order it is evaluated.
 *
 * <p>Carries more than the client needs — the client only reads {@code path} —
 * because the analysis surface and the client are looking at the same list and
 * a second DTO for the same thing would be a second thing to keep in step.
 *
 * @param path    the document to evaluate.
 * @param kind    what put it in the list: {@code library}, {@code app-script}
 *                or {@code program}.
 * @param name    library name, or {@code null} for an app-local script.
 * @param version library version, or {@code null}.
 * @param origin  where the document came from: {@code project}, {@code tenant}
 *                or {@code bundled}. A library that resolves to {@code bundled}
 *                is the addon's copy — worth seeing, because overriding it is a
 *                matter of putting a document at the same path.
 * @param askedBy which document asked for it, for a library. An app-script and
 *                the program are found, not asked for, so this is null there.
 */
@GenerateTypeScript("bistromath")
public record LoadedScript(
        String path,
        String kind,
        @Nullable String name,
        @Nullable String version,
        String origin,
        @Nullable String askedBy) {

    static final String LIBRARY = "library";
    static final String APP_SCRIPT = "app-script";
    static final String PROGRAM = "program";
}
