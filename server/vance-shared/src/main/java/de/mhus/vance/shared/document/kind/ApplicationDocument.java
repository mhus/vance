package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Manifest for a Vance "application folder" — the {@code _app.yaml}
 * file at the root of a folder turns the folder into a self-contained
 * domain-specific workspace, à la macOS {@code .app} bundles.
 *
 * <p>The discriminator pair sits in {@code $meta}:
 *
 * <pre>
 * $meta:
 *   kind: application
 *   app: calendar
 * </pre>
 *
 * <p>{@code kind} mirrors to {@link de.mhus.vance.shared.document.DocumentDocument#getKind()}
 * (indexed). {@code app} mirrors to {@code DocumentDocument.headers.app}
 * via the standard scalar-meta passthrough, so queries can find all
 * calendar-apps in a project without touching the body.
 *
 * <p>{@link #config} is the app-specific configuration, nested under
 * the app type's name (e.g. {@code config.calendar = { ... }}). The
 * nested form makes schema evolution cheap — a folder could in
 * future host two app faces ({@code calendar} + {@code wiki}) by
 * just adding the second sub-block.
 *
 * <p>Specs: {@code specification/doc-kind-application.md}.
 *
 * @param kind         always {@code "application"}.
 * @param app          required, free-form string. Known values today:
 *                     {@code "calendar"}. Future: {@code "kanban"},
 *                     {@code "wiki"}, {@code "book"}, {@code "research"}.
 * @param title        optional display title for the app.
 * @param description  optional free-form description.
 * @param config       app-specific config, keyed by app-name at the
 *                     top level: {@code config.get("calendar")} for
 *                     a calendar app. Codec helpers like
 *                     {@code CalendarsAppConfig} reinterpret the
 *                     untyped {@code Map<String, Object>} as typed
 *                     records on demand.
 * @param extra        forward-compat pass-through for unknown
 *                     top-level fields.
 */
public record ApplicationDocument(
        String kind,
        String app,
        @Nullable String title,
        @Nullable String description,
        Map<String, Object> config,
        Map<String, Object> extra) {

    public ApplicationDocument {
        if (kind == null || kind.isBlank()) kind = "application";
        if (app == null) app = "";
        if (config == null) config = new LinkedHashMap<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static ApplicationDocument empty(String app) {
        return new ApplicationDocument("application", app, null, null,
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }
}
