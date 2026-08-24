package de.mhus.vance.api.applications;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One place inside an application — the {@code ?entry=} of an inter-app link.
 *
 * @param handle opaque to everyone but the app that produced it: a page id in
 *               Workbook, a space-qualified slug in Wiki. Stored verbatim in the
 *               link, handed back verbatim when it is followed
 * @param label  what a human picks from
 * @param group  heading to sort under (a workbook section, a wiki space);
 *               {@code null} for ungrouped
 */
@GenerateTypeScript("applications")
public record ApplicationTargetDto(
        String handle,
        String label,
        @Nullable String group) {}
