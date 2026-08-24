package de.mhus.vance.api.applications;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Response of {@code GET /brain/{tenant}/applications} — the apps a link can
 * point at, in two lists rather than one merged one.
 *
 * <p>The split is not cosmetic: {@code starred} crosses projects and is the
 * user's own shortcut, {@code project} is everything in the project being
 * worked in. A single list would have to invent an ordering between "mine" and
 * "here" and would lose the label a picker needs to tell them apart. An app in
 * both appears only under {@code starred}.
 */
@GenerateTypeScript("applications")
public record ApplicationListResponse(
        List<ApplicationEntryDto> starred,
        List<ApplicationEntryDto> project) {}
