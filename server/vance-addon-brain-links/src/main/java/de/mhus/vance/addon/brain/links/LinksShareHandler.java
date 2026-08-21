package de.mhus.vance.addon.brain.links;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.milliways.ShareAvailability;
import de.mhus.vance.brain.milliways.ShareException;
import de.mhus.vance.brain.milliways.ShareHandler;
import de.mhus.vance.brain.milliways.ShareRequest;
import de.mhus.vance.brain.milliways.ShareResult;
import de.mhus.vance.brain.milliways.ShareScope;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Milliways handler: puts the shared link into a links app as an entry.
 *
 * <p>The first handler that shares <em>inwards</em>, the first contributed by
 * an <em>addon</em> (Milliways has no line about it), and the first whose
 * availability reads the <em>subject</em> rather than only the project — a
 * share without a link has nothing to add here.
 *
 * <p><b>What is written and what is not.</b> {@code url} and {@code title}
 * come from the subject; the form only asks where it should land and for an
 * optional note. {@code teaser} and {@code image} stay empty on purpose: an
 * empty teaser means "whatever the page says today", resolved live from the
 * link-preview proxy, and a copy of that in the manifest would go stale where
 * nobody refreshes it — see {@link LinkEntry}. The search hit's snippet
 * describes the page and is therefore exactly what must <em>not</em> be
 * stored there; the sharer's own remark goes into {@code note}.
 *
 * <p>Writing goes through {@link LinksManifestOps#addEntry}, the same method
 * the app's own controller calls. No second place knows the manifest.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LinksShareHandler implements ShareHandler {

    public static final String ID = "links";

    static final String FIELD_APP = "app";
    static final String FIELD_GROUP = "group";
    static final String FIELD_POSITION = "position";
    static final String FIELD_NOTE = "note";

    static final String POSITION_TOP = "top";
    static final String POSITION_BOTTOM = "bottom";

    /** Separator in the {@code app} value. Neither part may contain it. */
    private static final String APP_VALUE_SEPARATOR = "|";

    private final StarredService starredService;
    private final LinksManifestOps manifestOps;
    private final LinksStore store;
    private final PermissionService permissionService;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, String> label() {
        return Map.of("en", "Add Link", "de", "Link hinzufügen");
    }

    @Override
    public ShareAvailability availability(ShareScope scope) {
        if (scope.subject().link() == null) {
            // A document share has no URL, and a `vance:` entry would be a card
            // with no preview and no title snapshot. Linking a document is what
            // the Binder is for.
            return ShareAvailability.unavailable("Nothing to add: this share has no link");
        }
        if (apps(scope).isEmpty()) {
            return ShareAvailability.unavailable("No links app in your starred list");
        }
        return ShareAvailability.ready();
    }

    @Override
    public List<FormFieldDto> form(ShareScope scope) {
        List<StarredItem> apps = apps(scope);
        List<FormFieldDto> fields = new ArrayList<>(4);

        // One app needs no question — same cut the smtp handler makes for a
        // single pack. With one app the group can be a real choice, because
        // then it does not depend on a selection that has not happened yet.
        if (apps.size() > 1) {
            List<FormChoiceDto> choices = new ArrayList<>(apps.size());
            for (StarredItem app : apps) {
                choices.add(FormChoiceDto.builder()
                        .value(appValue(app))
                        .label(Map.of("en", appLabel(app, scope)))
                        .build());
            }
            fields.add(FormFieldDto.builder()
                    .name(FIELD_APP)
                    .type("select")
                    .label(Map.of("en", "Add to", "de", "Hinzufügen zu"))
                    .required(true)
                    .defaultValue(appValue(apps.get(0)))
                    .choices(choices)
                    .build());
            // Free text, not a select: the group list belongs to the app that
            // has not been picked yet, and dependent choices are not part of
            // the form grammar. No loss — a typed name becomes a heading.
            fields.add(FormFieldDto.builder()
                    .name(FIELD_GROUP)
                    .type("string")
                    .label(Map.of("en", "Group", "de", "Gruppe"))
                    .help(Map.of(
                            "en", "Empty for the lead group; a new name becomes a heading.",
                            "de", "Leer für die führende Gruppe; ein neuer Name wird eine Überschrift."))
                    .build());
        } else {
            fields.add(groupSelect(scope, apps.get(0)));
        }

        fields.add(FormFieldDto.builder()
                .name(FIELD_POSITION)
                .type("select")
                .label(Map.of("en", "Position", "de", "Position"))
                .defaultValue(POSITION_BOTTOM)
                .choices(List.of(
                        FormChoiceDto.builder().value(POSITION_TOP)
                                .label(Map.of("en", "Top of the group", "de", "Oben in der Gruppe"))
                                .build(),
                        FormChoiceDto.builder().value(POSITION_BOTTOM)
                                .label(Map.of("en", "Bottom of the group", "de", "Unten in der Gruppe"))
                                .build()))
                .build());
        // Optional, unlike the inbox handler's reason: there a human reads the
        // sentence, here it is a remark on a card with no addressee.
        fields.add(FormFieldDto.builder()
                .name(FIELD_NOTE)
                .type("textarea")
                .label(Map.of("en", "Note", "de", "Notiz"))
                .help(Map.of(
                        "en", "Why this list has it. The page's own summary comes from the preview.",
                        "de", "Warum diese Liste ihn hat. Die Seitenbeschreibung kommt aus der Vorschau."))
                .rows(3)
                .build());
        return List.copyOf(fields);
    }

    @Override
    public ShareResult share(ShareRequest request) {
        ShareScope scope = request.scope();
        String link = scope.subject().link();
        if (link == null) {
            throw new ShareException("Nothing to add: this share has no link");
        }
        StarredItem app = pickApp(scope, request.string(FIELD_APP));
        String folder = folderOf(app);

        // The app may live in another project than the share: the starred list
        // is per user, across projects. So the write is authorized against the
        // app's project, not the one the sharer happens to be in.
        permissionService.enforce(
                scope.ctx(),
                new Resource.Project(scope.tenantId(), app.project()),
                Action.WRITE);

        LinksManifestOps.Position position = POSITION_TOP.equals(request.string(FIELD_POSITION))
                ? LinksManifestOps.Position.TOP
                : LinksManifestOps.Position.BOTTOM;
        LinksManifestOps.LinkFields fields = new LinksManifestOps.LinkFields(
                scope.displayTitle(),
                /*teaser*/ null,
                /*image*/ null,
                request.string(FIELD_GROUP),
                /*tags*/ null,
                request.string(FIELD_NOTE));

        boolean added = manifestOps.addEntry(
                scope.tenantId(), app.project(), folder, link, fields, position, scope.sharer());

        Map<String, Object> details = ShareResult.newDetails();
        details.put("app", app.project() + "/" + app.path());
        details.put("added", added);
        String group = request.string(FIELD_GROUP);
        if (group != null) details.put("group", group);
        details.put("position", position.name().toLowerCase(java.util.Locale.ROOT));

        String name = appName(app);
        log.info("Milliways links share: url='{}' app='{}/{}' added={}",
                link, app.project(), folder, added);
        // Already there is not a refusal — nothing is broken, it is just there.
        // Saying "added" would be a lie about the state of the list.
        return new ShareResult(
                added ? "Added to " + name : "Already in " + name,
                details);
    }

    // ──────────────────── internals ────────────────────

    /**
     * The sharer's starred links apps, highlighted ones first.
     *
     * <p>{@code StarredService} returns file order and deliberately does not
     * let {@code highlight} reorder — that rule is about never letting a visual
     * emphasis pick a target silently. A list put in front of a human is the
     * other case, so the ordering happens here, in the presentation.
     */
    private List<StarredItem> apps(ShareScope scope) {
        List<StarredItem> apps = new ArrayList<>(starredService.listByType(
                scope.tenantId(), scope.sharer(), LinksApplication.APP_NAME));
        // Stable: only highlight decides, equal ones keep file order.
        apps.sort(Comparator.comparing(StarredItem::highlight).reversed());
        return List.copyOf(apps);
    }

    private StarredItem pickApp(ShareScope scope, @Nullable String requested) {
        List<StarredItem> apps = apps(scope);
        if (apps.isEmpty()) {
            throw new ShareException("No links app in your starred list");
        }
        if (requested == null) return apps.get(0);
        for (StarredItem app : apps) {
            if (appValue(app).equals(requested)) return app;
        }
        // A value the form never offered — the starred list changed, or the
        // submission was hand-made. Not a project to write into either way.
        throw new ShareException("Unknown links app '" + requested + "'");
    }

    /** Groups the app already declares, offered as a choice plus "no group". */
    private FormFieldDto groupSelect(ShareScope scope, StarredItem app) {
        List<FormChoiceDto> choices = new ArrayList<>();
        choices.add(FormChoiceDto.builder()
                .value("")
                .label(Map.of("en", "(no group)", "de", "(ohne Gruppe)"))
                .build());
        for (String group : groupsOf(scope, app)) {
            choices.add(FormChoiceDto.builder()
                    .value(group)
                    .label(Map.of("en", group))
                    .build());
        }
        return FormFieldDto.builder()
                .name(FIELD_GROUP)
                .type("select")
                .label(Map.of("en", "Group", "de", "Gruppe"))
                .defaultValue("")
                .choices(choices)
                .build();
    }

    /**
     * A links app whose manifest cannot be read is not worth a broken form:
     * the group list degrades to "no group" and the share itself will report
     * the real problem.
     */
    private List<String> groupsOf(ShareScope scope, StarredItem app) {
        try {
            return store.load(scope.tenantId(), app.project(), folderOf(app)).config().groups();
        } catch (RuntimeException e) {
            log.debug("Links share: cannot read groups of '{}/{}': {}",
                    app.project(), app.path(), e.toString());
            return List.of();
        }
    }

    /** {@code <folder>/_app.yaml} → {@code <folder>}. */
    private static String folderOf(StarredItem app) {
        String path = app.path();
        String suffix = "/" + VanceApplication.APP_MANIFEST;
        if (path.endsWith(suffix)) {
            return LinksStore.normaliseFolder(path.substring(0, path.length() - suffix.length()));
        }
        return LinksStore.normaliseFolder(path);
    }

    private static String appValue(StarredItem app) {
        return app.project() + APP_VALUE_SEPARATOR + app.path();
    }

    /** Title if the starred entry carries one, else the folder. */
    private static String appName(StarredItem app) {
        String title = app.title();
        return title == null || title.isBlank() ? folderOf(app) : title;
    }

    /** The project comes along when the app is not in the one being shared from. */
    private static String appLabel(StarredItem app, ShareScope scope) {
        String name = appName(app);
        return app.project().equals(scope.projectId()) ? name : name + " (" + app.project() + ")";
    }
}
