package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * What an app loads, and what is wrong with it.
 *
 * <p>Three lists rather than one with severities, because they are three
 * different questions and a reader wants them apart: *what runs*, *what I
 * should look at*, and *what is not there at all*. A single list sorted by
 * severity makes the third case — a require nobody can satisfy — look like a
 * detail of the first.
 *
 * @param scripts  the load order, dependencies before dependents, program last.
 * @param warnings resolutions that went ahead but not as written: a version
 *                 conflict settled by taking the highest, a cycle broken.
 * @param missing  requires that resolved to nothing. The app still runs — a
 *                 missing library is a broken program, not a broken app, and
 *                 refusing to start would hide which of the two it is.
 */
@GenerateTypeScript("bistromath")
public record RequireReport(
        List<LoadedScript> scripts,
        List<String> warnings,
        List<String> missing) {

    public RequireReport {
        if (scripts == null) scripts = List.of();
        if (warnings == null) warnings = List.of();
        if (missing == null) missing = List.of();
    }

    static RequireReport empty() {
        return new RequireReport(List.of(), List.of(), List.of());
    }

    boolean clean() {
        return warnings.isEmpty() && missing.isEmpty();
    }
}
