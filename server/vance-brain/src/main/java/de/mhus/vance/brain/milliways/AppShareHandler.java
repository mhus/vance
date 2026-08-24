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
 * <p><b>The form asks one flat question and a note, nothing else.</b> A handler
 * declares its fields once, before a target is chosen, so per-app fields (a
 * Kanban column, a GTD list, a link group) would need a two-step form. Instead
 * the app and its intake places are rows of the <em>same</em> select — the app
 * itself first, then {@code App › place} for whatever it offers under
 * {@link VanceApplication.TargetPurpose#INTAKE}. An app with no places behaves
 * exactly as before.
 *
 * <p>A share stays a hand-off, not an edit: the places are the app's own intakes
 * (the lead group, the inbox, the backlog), not arbitrary positions. Refining
 * still happens in the app, which is also where the sorting lives.
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

    /**
     * Separator in the {@code app} value ({@code project|path[|handle]}).
     * No part may contain it — enforced for the handle where handles are
     * produced ({@code AppTarget}), so no consumer has to escape them.
     */
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
        List<Choice> choices = choices(scope);
        List<FormFieldDto> fields = new ArrayList<>(2);
        // One choice needs no question — the same cut the smtp handler makes
        // for a single pack.
        if (choices.size() > 1) {
            List<FormChoiceDto> dtos = new ArrayList<>(choices.size());
            for (Choice choice : choices) {
                dtos.add(FormChoiceDto.builder()
                        .value(choice.value())
                        .label(Map.of("en", choice.label()))
                        .build());
            }
            fields.add(FormFieldDto.builder()
                    .name(FIELD_APP)
                    .type("select")
                    .label(Map.of("en", "Add to", "de", "Hinzufügen zu"))
                    .required(true)
                    .defaultValue(choices.get(0).value())
                    .choices(dtos)
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
        Choice choice = pick(scope, request.string(FIELD_APP));
        Candidate target = choice.candidate();

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
                        intakeOf(scope), request.string(FIELD_NOTE), scope.sharer(),
                        choice.target()));

        Map<String, Object> details = ShareResult.newDetails();
        details.put("app", target.item().project() + "/" + target.item().path());
        details.put("appType", target.app().appName());
        details.put("created", result.created());
        if (choice.target() != null) details.put("target", choice.target());

        log.info("Milliways app share: type='{}' app='{}/{}' target='{}' created={}",
                target.app().appName(), target.item().project(), folder,
                choice.target() == null ? "" : choice.target(), result.created());
        // Already there is not a refusal — nothing is broken, it is just there.
        String where = choice.target() == null
                ? result.label() : result.label() + " › " + choice.target();
        return new ShareResult(
                (result.created() ? "Added to " : "Already in ") + where,
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

    private Choice pick(ShareScope scope, @Nullable String requested) {
        List<Choice> choices = choices(scope);
        if (choices.isEmpty()) {
            throw new ShareException("No starred app takes this");
        }
        if (requested == null) return choices.get(0);
        for (Choice choice : choices) {
            if (choice.value().equals(requested)) return choice;
        }
        // A value the form never offered — the starred list changed, or the
        // submission was hand-made. Not a project to write into either way.
        throw new ShareException("Unknown app '" + requested + "'");
    }

    /**
     * One row of the picker: an app, optionally at one of its intake places.
     *
     * <p><b>Flat, not a dependent field.</b> {@code form()} is declared once,
     * before anything is chosen, so a per-app "which group" select would need a
     * two-step form — the reason
     * {@code planning/milliways-app-handler.md} §2 dropped group and position in
     * the first place. Putting the place into the *same* list keeps one request
     * and one field, at the price of more rows.
     *
     * <p>The list stays short because {@code INTAKE} is short by nature (a
     * backlog, an inbox, a handful of groups) and because the app decides how
     * many it offers. If it ever gets long, the answer is a dependent field in
     * the form grammar, not silently truncating here.
     */
    private record Choice(Candidate candidate, @Nullable String target, String label) {

        /** {@code project|path} for the app itself, {@code project|path|handle} for a place. */
        String value() {
            String base = candidate.item().project() + APP_VALUE_SEPARATOR + candidate.item().path();
            return target == null ? base : base + APP_VALUE_SEPARATOR + target;
        }
    }

    /**
     * The picker rows: every accepting app, each followed by its intake places.
     *
     * <p>The app itself stays first in its block — it is the behaviour every
     * share had before places existed, and the one a sharer who does not care
     * should get by pressing return.
     */
    private List<Choice> choices(ShareScope scope) {
        List<Choice> out = new ArrayList<>();
        for (Candidate candidate : candidates(scope)) {
            String appLabel = label(candidate, scope);
            out.add(new Choice(candidate, null, appLabel));
            for (VanceApplication.AppTarget target : intakeTargets(candidate, scope)) {
                out.add(new Choice(candidate, target.handle(), appLabel + " › " + target.label()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * An app that throws while listing its places loses the places, not its row
     * — same rule as {@link #accepts}: one broken app must not hide the others.
     *
     * <p><b>Cost, stated because it is the asymmetry here.</b>
     * {@code ApplicationsController} splits listing apps from listing one app's
     * places precisely because the second is expensive — and this asks every
     * candidate on every {@code form()}, i.e. on every dialog open. It is cheap
     * today: only the links app answers {@code INTAKE} at all (a manifest read),
     * everyone else checks {@code purpose} and returns immediately, and the
     * candidate list is the sharer's favourites. The first {@code INTAKE}
     * implementation that walks a folder makes this the wrong shape, and the
     * answer then is a dependent form field — the same conclusion the
     * {@link Choice} javadoc reaches about the list's <em>length</em>.
     */
    private List<VanceApplication.AppTarget> intakeTargets(Candidate candidate, ShareScope scope) {
        try {
            return candidate.app().targets(new VanceApplication.TargetsContext(
                    scope.tenantId(), candidate.item().project(), folderOf(candidate.item()),
                    scope.sharer(), VanceApplication.TargetPurpose.INTAKE, Map.of()));
        } catch (RuntimeException e) {
            log.warn("App '{}' failed to list intake targets: {}",
                    candidate.app().appName(), e.toString());
            return List.of();
        }
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

    /** Title if the starred entry carries one, else the folder; project when foreign. */
    private static String label(Candidate candidate, ShareScope scope) {
        StarredItem item = candidate.item();
        String title = item.title();
        String name = title == null || title.isBlank() ? folderOf(item) : title;
        return item.project().equals(scope.projectId()) ? name : name + " (" + item.project() + ")";
    }
}
