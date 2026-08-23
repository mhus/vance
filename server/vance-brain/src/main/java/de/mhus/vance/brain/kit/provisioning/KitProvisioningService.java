package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.brain.kit.KitService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Brings a project in line with the kit desired-lists its provisioning
 * entries report.
 *
 * <p>Two steps, and they are one code path with the ordinary update:
 * <ol>
 *   <li>provision — what a mechanism says should be here and has no
 *       install record yet gets installed
 *   <li>update — every record checked against its source
 * </ol>
 * Only the first is implemented here. The second already exists as
 * {@code KitService.updateAllInstalled}, and deciding <em>when</em> to run
 * it belongs to the check (see {@code planning/kit-ode-provisioning.md}
 * §9), not to the bootstrap.
 *
 * <p><b>Additive only.</b> A line removed from the document uninstalls
 * nothing. A desired-state runner that deletes documents when a line
 * disappears turns a typo into data loss, and {@code uninstall} is a verb
 * somebody types on purpose. Named honestly: this is bootstrap-and-catch-up,
 * not a reconciler.
 *
 * <p><b>Nothing here throws at its caller.</b> Provisioning runs from a
 * lifecycle event and from a scheduler tick, where there is nobody to show
 * an exception to and where failing loudly would take a project's startup
 * with it. Each entry and each kit is attempted on its own so one
 * unreachable host does not cost the others; what went wrong is logged and
 * returned.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitProvisioningService {

    /** Recorded as the actor on documents this path writes. */
    public static final String ACTOR = "_provisioning";

    /**
     * Projects with a run in flight; the flag says whether another was
     * asked for while it ran.
     *
     * <p>Two triggers can fire close together — an edit to the document
     * lands while the project-start run is still installing — and kit
     * install is not something to do twice at once in one project. Dropping
     * the second request outright would be worse than it looks: the first
     * run may have read the document <em>before</em> the edit, so the edit
     * would silently take effect only at the next tick. Hence coalescing
     * rather than a plain lock.
     */
    private final ConcurrentMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    private final KitProvisioningLoader loader;
    private final KitProvisioningHandlers handlers;
    private final KitRecordStore recordStore;
    private final KitService kitService;

    /**
     * Outcome of one run — for the log, and for a caller that wants to
     * say something about it.
     *
     * @param installed kits newly installed by this run
     * @param updated kits refreshed by this run because their source moved
     *        on and the entry permitted it unattended
     * @param alreadyPresent desired kits that already had a record
     * @param withheld desired kits that are missing but whose entry does
     *        not permit installing them unattended
     * @param failures one line per entry or kit that did not work out
     */
    public record Outcome(
            List<String> installed,
            List<String> updated,
            List<String> alreadyPresent,
            List<String> withheld,
            List<String> failures) {

        static Outcome nothing() {
            return new Outcome(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        boolean isEmpty() {
            return installed.isEmpty() && updated.isEmpty() && alreadyPresent.isEmpty()
                    && withheld.isEmpty() && failures.isEmpty();
        }
    }

    /**
     * Run, or fold into a run that is already going for this project.
     *
     * <p>What every trigger should call. Returns nothing, because the caller
     * that gets folded in has no outcome to be given — and no trigger reads
     * one anyway.
     */
    public void provisionCoalesced(String tenantId, String projectId) {
        String key = tenantId + '/' + projectId;
        if (inFlight.putIfAbsent(key, Boolean.FALSE) != null) {
            inFlight.put(key, Boolean.TRUE);
            log.debug("Provisioning of {} already running — folded in", key);
            return;
        }
        try {
            while (true) {
                provision(tenantId, projectId);
                // Re-read rather than remove-then-check: a request arriving
                // between the two would otherwise be lost, which is the whole
                // failure this method exists to avoid.
                Boolean again = inFlight.replace(key, Boolean.FALSE);
                if (Boolean.TRUE.equals(again)) continue;
                // Conditional remove, and another round if it fails. An
                // unconditional remove in a finally block re-opened the very
                // window this loop closes: a second trigger arriving between
                // the replace above and the removal reads "already running",
                // sets TRUE — and the removal then throws that TRUE away.
                if (inFlight.remove(key, Boolean.FALSE)) return;
            }
        } catch (RuntimeException | Error e) {
            // The loop owns the removal on its normal exits; an unexpected
            // throw must not leave the project marked as running forever.
            inFlight.remove(key);
            throw e;
        }
    }

    /**
     * Install what this project's provisioning declares and does not have.
     *
     * <p>Cheap when nothing is declared: one document lookup and out. That
     * is what makes it safe to call on every project start and on every
     * tick, for every project.
     */
    public Outcome provision(String tenantId, String projectId) {
        List<String> installed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        List<KitProvisioningEntry> entries;
        try {
            entries = loader.load(tenantId, projectId);
        } catch (RuntimeException e) {
            // A malformed document is the writer's mistake and has to be
            // visible, but it must not take a project start with it.
            log.warn("Provisioning of {}/{} skipped — {}", tenantId, projectId, e.toString());
            return new Outcome(
                    List.of(), List.of(), List.of(), List.of(), List.of(e.toString()));
        }
        if (entries.isEmpty()) {
            return Outcome.nothing();
        }

        for (KitProvisioningEntry entry : entries) {
            List<DesiredKit> desired;
            try {
                desired = handlers.discover(tenantId, projectId, entry);
            } catch (RuntimeException e) {
                // One unreachable host must not cost the project the kits of
                // its other entries.
                log.warn("Provisioning of {}/{}: entry {} could not be asked — {}",
                        tenantId, projectId, entry, e.toString());
                failures.add(entry.type() + " " + entry.url() + ": " + e);
                continue;
            }
            for (DesiredKit kit : desired) {
                try {
                    apply(tenantId, projectId, entry, kit,
                            installed, updated, present, withheld);
                } catch (RuntimeException e) {
                    log.warn("Provisioning of {}/{}: kit '{}' from {} failed — {}",
                            tenantId, projectId, kit.path(), kit.sourceUrl(), e.toString());
                    failures.add(kit.path() + ": " + e);
                }
            }
        }

        Outcome outcome = new Outcome(
                List.copyOf(installed), List.copyOf(updated), List.copyOf(present),
                List.copyOf(withheld), List.copyOf(failures));
        if (!outcome.isEmpty()) {
            log.info("Provisioning of {}/{}: installed={} updated={} present={} withheld={}"
                            + " failures={}",
                    tenantId, projectId, installed, updated, present, withheld, failures);
        }
        return outcome;
    }

    private void apply(
            String tenantId,
            String projectId,
            KitProvisioningEntry entry,
            DesiredKit kit,
            List<String> installed,
            List<String> updated,
            List<String> present,
            List<String> withheld) {

        @Nullable KitInstalledRecordDto record =
                recordStore.findByOrigin(tenantId, projectId, kit.sourceUrl(), kit.path());
        if (record != null) {
            if (kit.authority().mayUpdateInstalled() && stampDiffers(record, kit)) {
                // The entry granted unattended refresh and the source moved on.
                // Not routed through updateInstalled: that rebuilds the request
                // from the record and would lose the params and the new stamp.
                kitService.update(tenantId, requestFor(projectId, entry, kit), ACTOR,
                        SettingWriteOrigin.USER);
                updated.add(kit.path());
                return;
            }
            // Here and either current or not ours to refresh. Whether a person
            // should hear about it is the check's question (§9).
            present.add(kit.path());
            return;
        }
        if (!kit.authority().mayInstallNew()) {
            // The entry did not grant unattended installation. Recorded rather
            // than silently dropped: at NOTIFY this is exactly the divergence
            // the report is supposed to be about.
            withheld.add(kit.path());
            return;
        }

        kitService.install(tenantId, requestFor(projectId, entry, kit), ACTOR,
                SettingWriteOrigin.USER);
        installed.add(kit.path());
    }

    /** The same request either way — only the mode differs, and the caller picks it. */
    private static KitImportRequestDto requestFor(
            String projectId, KitProvisioningEntry entry, DesiredKit kit) {
        return KitImportRequestDto.builder()
                .projectId(projectId)
                .source(KitInheritDto.builder()
                        .url(kit.sourceUrl())
                        .path(kit.path())
                        .build())
                .token(entry.token())
                .params(kit.params())
                // Remembered on the record so a later check is one comparison.
                .provisioningStamp(KitProvisioningStamp.of(kit.revision(), kit.params()))
                .build();
    }

    private static boolean stampDiffers(KitInstalledRecordDto record, DesiredKit kit) {
        return KitProvisioningStamp.differs(
                record.getOrigin() == null ? null : record.getOrigin().getProvisioningStamp(),
                kit.revision(), kit.params());
    }

}
