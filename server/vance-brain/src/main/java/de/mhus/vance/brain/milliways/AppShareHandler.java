package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplicationRegistry;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Hands the subject to one of the sharer's starred apps as a new entry.
 *
 * <p>One handler for <em>every</em> app that can take something, not one per
 * app type. "Send this to my todos" is a capability that Kanban, GTD and Issues
 * share, and the only place that knows whether an app has it is the app:
 * {@link VanceApplication#acceptsShare} declares it,
 * {@link VanceApplication#acceptShare} does it. Adding the fourth app is two
 * methods, not another handler — and the menu keeps one entry where the user
 * expects one.
 *
 * <p><b>The form asks the app and a note, nothing else.</b> A handler declares
 * its fields once, before a target is chosen, so per-app fields (a Kanban
 * column, a GTD list, a link group) would need a two-step form. Instead each
 * app writes into its own <em>intake</em> — the lead group, the inbox, the
 * backlog. A share is a hand-off, not an edit; refining happens in the app,
 * which is also where the sorting lives.
 *
 * <p>Lives in brain rather than in an addon because {@link VanceApplication}
 * does: the authorization then sits in one place instead of in every addon.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppShareHandler implements ShareHandler {

    public static final String ID = "app";

    static final String FIELD_APP = "app";
    static final String FIELD_NOTE = "note";

    /** Separator in the {@code app} value. Neither part may contain it. */
    private static final String APP_VALUE_SEPARATOR = "|";

    private final StarredService starredService;
    private final VanceApplicationRegistry applications;
    private final PermissionService permissionService;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, String> label() {
        return Map.of("en", "Add to app", "de", "In eine App");
    }

    @Override
    public ShareAvailability availability(ShareScope scope) {
        if (candidates(scope).isEmpty()) {
            // Two reasons, told apart: nothing starred at all is a different
            // thing to fix than nothing starred that can take *this*.
            boolean anyApp = starredApps(scope).findAny().isPresent();
            return ShareAvailability.unavailable(anyApp
                    ? "None of your starred apps takes this"
                    : "No app in your starred list");
        }
        return ShareAvailability.ready();
    }

    @Override
    public List<FormFieldDto> form(ShareScope scope) {
        List<Candidate> candidates = candidates(scope);
        List<FormFieldDto> fields = new ArrayList<>(2);
        // One candidate needs no question — the same cut the smtp handler makes
        // for a single pack.
        if (candidates.size() > 1) {
            List<FormChoiceDto> choices = new ArrayList<>(candidates.size());
            for (Candidate candidate : candidates) {
                choices.add(FormChoiceDto.builder()
                        .value(value(candidate.item()))
                        .label(Map.of("en", label(candidate, scope)))
                        .build());
            }
            fields.add(FormFieldDto.builder()
                    .name(FIELD_APP)
                    .type("select")
                    .label(Map.of("en", "Add to", "de", "Hinzufügen zu"))
                    .required(true)
                    .defaultValue(value(candidates.get(0).item()))
                    .choices(choices)
                    .build());
        }
        // Optional, unlike the inbox handler's reason: there a human reads the
        // sentence, here it is a remark on an entry with no addressee.
        fields.add(FormFieldDto.builder()
                .name(FIELD_NOTE)
                .type("textarea")
                .label(Map.of("en", "Note", "de", "Notiz"))
                .help(Map.of(
                        "en", "Your own remark. It lands with the entry.",
                        "de", "Die eigene Bemerkung. Sie landet beim Eintrag."))
                .rows(3)
                .build());
        return List.copyOf(fields);
    }

    @Override
    public ShareResult share(ShareRequest request) {
        ShareScope scope = request.scope();
        Candidate target = pick(scope, request.string(FIELD_APP));

        // The app may live in another project than the share: the starred list
        // is per user, across projects. So the write is authorized against the
        // app's project, not the one the sharer happens to be in.
        permissionService.enforce(
                scope.ctx(),
                new Resource.Project(scope.tenantId(), target.item().project()),
                Action.WRITE);

        String folder = folderOf(target.item());
        VanceApplication.ShareIntakeResult result = target.app().acceptShare(
                new VanceApplication.ShareIntakeContext(
                        scope.tenantId(), target.item().project(), folder,
                        intakeOf(scope), request.string(FIELD_NOTE), scope.sharer()));

        Map<String, Object> details = ShareResult.newDetails();
        details.put("app", target.item().project() + "/" + target.item().path());
        details.put("appType", target.app().appName());
        details.put("created", result.created());

        log.info("Milliways app share: type='{}' app='{}/{}' created={}",
                target.app().appName(), target.item().project(), folder, result.created());
        // Already there is not a refusal — nothing is broken, it is just there.
        return new ShareResult(
                (result.created() ? "Added to " : "Already in ") + result.label(),
                details);
    }

    // ──────────────────── internals ────────────────────

    /** A starred app instance together with the bean that speaks for its type. */
    private record Candidate(StarredItem item, VanceApplication app) {
    }

    /**
     * Starred apps that can take this subject, highlighted ones first.
     *
     * <p>{@code StarredService} returns file order and deliberately does not
     * let {@code highlight} reorder — that rule is about never letting a visual
     * emphasis pick a target silently. A list put in front of a human is the
     * other case, so the ordering happens here, in the presentation.
     */
    private List<Candidate> candidates(ShareScope scope) {
        VanceApplication.ShareIntake intake = intakeOf(scope);
        List<Candidate> out = new ArrayList<>();
        for (StarredItem item : starredApps(scope).toList()) {
            String type = item.type();
            if (type == null) continue; // unreachable via starredApps; keeps the type checker honest
            Optional<VanceApplication> app = applications.find(type);
            if (app.isEmpty()) continue;
            if (!accepts(app.get(), intake)) continue;
            out.add(new Candidate(item, app.get()));
        }
        // Stable: only highlight decides, equal ones keep file order.
        out.sort(Comparator.comparing((Candidate c) -> c.item().highlight()).reversed());
        return List.copyOf(out);
    }

    /**
     * Every starred entry that is an application, in file order.
     *
     * <p>Filtered on {@code type != null} rather than on the {@code application}
     * kind string: {@code type} <em>is</em> "the {@code app:} of an application
     * manifest and {@code null} otherwise" ({@link StarredItem}), so this asks
     * the field that carries the meaning instead of repeating a kind name that
     * {@code StarredService} keeps to itself.
     */
    private Stream<StarredItem> starredApps(ShareScope scope) {
        return starredService.listResolvable(scope.tenantId(), scope.sharer()).stream()
                .filter(i -> i.type() != null);
    }

    /**
     * An app that throws while answering is treated as not accepting rather
     * than taken down with the whole menu — one broken app must not hide the
     * others.
     */
    private boolean accepts(VanceApplication app, VanceApplication.ShareIntake intake) {
        try {
            return app.acceptsShare(intake);
        } catch (RuntimeException e) {
            log.warn("App '{}' failed to answer acceptsShare: {}", app.appName(), e.toString());
            return false;
        }
    }

    private Candidate pick(ShareScope scope, @Nullable String requested) {
        List<Candidate> candidates = candidates(scope);
        if (candidates.isEmpty()) {
            throw new ShareException("No starred app takes this");
        }
        if (requested == null) return candidates.get(0);
        for (Candidate candidate : candidates) {
            if (value(candidate.item()).equals(requested)) return candidate;
        }
        // A value the form never offered — the starred list changed, or the
        // submission was hand-made. Not a project to write into either way.
        throw new ShareException("Unknown app '" + requested + "'");
    }

    private static VanceApplication.ShareIntake intakeOf(ShareScope scope) {
        ShareSubject subject = scope.subject();
        return new VanceApplication.ShareIntake(
                scope.displayTitle(), subject.link(), subject.snippet(), subject.hasDocument());
    }

    /** {@code <folder>/_app.yaml} → {@code <folder>}. */
    private static String folderOf(StarredItem item) {
        String path = item.path();
        String suffix = "/" + VanceApplication.APP_MANIFEST;
        return path.endsWith(suffix)
                ? path.substring(0, path.length() - suffix.length())
                : path;
    }

    private static String value(StarredItem item) {
        return item.project() + APP_VALUE_SEPARATOR + item.path();
    }

    /** Title if the starred entry carries one, else the folder; project when foreign. */
    private static String label(Candidate candidate, ShareScope scope) {
        StarredItem item = candidate.item();
        String title = item.title();
        String name = title == null || title.isBlank() ? folderOf(item) : title;
        return item.project().equals(scope.projectId()) ? name : name + " (" + item.project() + ")";
    }
}
