package de.mhus.vance.api.applications;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One application instance a link can point at.
 *
 * <p>{@code project} is carried even for same-project entries: the starred list
 * crosses projects, so a picker has to build {@code vance://<project>/<path>}
 * for some rows and {@code vance:/<path>} for others, and guessing from a
 * missing field is how that goes wrong.
 *
 * <p>Deliberately says nothing about whether the app <em>has</em> places. That
 * would mean asking every app in the list, and asking costs a folder scan —
 * fourteen of them for a listing that is drawn before anyone has chosen
 * anything. The places are fetched once an app is picked.
 *
 * @param path the manifest path ({@code <folder>/_app.yaml}) — the document a
 *             link addresses
 * @param app  the {@code app:} discriminator ({@code workbook}, {@code wiki}, …)
 */
@GenerateTypeScript("applications")
public record ApplicationEntryDto(
        String project,
        String path,
        String app,
        @Nullable String title,
        @Nullable String icon) {}
