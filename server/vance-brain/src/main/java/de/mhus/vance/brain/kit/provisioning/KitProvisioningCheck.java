package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Asks whether a project's provisioned kits are still what their sources
 * would give it, and says so — without installing anything.
 *
 * <p><b>Checking and installing are different acts.</b> A timer that
 * rewrites authored documents is not the same as one that swaps a cache:
 * {@code keep} is the default policy precisely because somebody may have
 * edited. A run that installs unasked turns „I adjusted this" into „it
 * changed under me at 4:17". So the periodic path reports; installing
 * happens on an answer, on the next {@code kit update}, or unattended only
 * where the entry granted it (see
 * {@code planning/kit-ode-provisioning.md} §9).
 *
 * <p><b>Two divergences, different sizes.</b> A kit that is here but whose
 * source moved on is a content change; a kit the source wants here that
 * is not is a change to the project's tool surface. They are reported
 * separately and permitted separately.
 *
 * <p><b>One item per divergence, not one per tick.</b> Six unanswered
 * notices a day are a reason to switch the feature off, so an open item
 * for the same kit suppresses the next. The open item is also the memory
 * that avoids a „last seen" collection — it is already persistent and
 * queryable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitProvisioningCheck {

    /** Tag every notice carries, and the handle the duplicate guard queries by. */
    public static final String TAG = "kit-provisioning";

    /** Marks what an item is about, so its kit can be recognised again. */
    static final String EFFECT_TYPE = "kit.provisioning.divergence";

    private final KitProvisioningLoader loader;
    private final KitProvisioningHandlers handlers;
    private final KitRecordStore recordStore;
    private final InboxItemService inboxItems;

    /** What one check found. */
    public record Report(List<String> changed, List<String> missing, List<String> reported) {}

    /**
     * Compare this project's provisioning against what is installed.
     *
     * <p>Cheap by construction: one document lookup when nothing is
     * declared, and one capabilities call per entry otherwise. Nothing is
     * downloaded and nothing is written except an inbox item.
     */
    public Report check(String tenantId, String projectId) {
        List<String> changed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> reported = new ArrayList<>();

        List<KitProvisioningEntry> entries;
        try {
            entries = loader.load(tenantId, projectId);
        } catch (RuntimeException e) {
            log.warn("Provisioning check of {}/{} skipped — {}", tenantId, projectId, e.toString());
            return new Report(List.of(), List.of(), List.of());
        }
        if (entries.isEmpty()) return new Report(List.of(), List.of(), List.of());

        for (KitProvisioningEntry entry : entries) {
            List<DesiredKit> desired;
            try {
                desired = handlers.discover(tenantId, projectId, entry);
            } catch (RuntimeException e) {
                // A host that is down is not a divergence. Reporting it as one
                // would fill an inbox with notices about somebody else's outage.
                log.debug("Provisioning check of {}/{}: entry {} unreachable — {}",
                        tenantId, projectId, entry, e.toString());
                continue;
            }
            for (DesiredKit kit : desired) {
                Divergence divergence = classify(tenantId, projectId, kit);
                if (divergence == null) continue;
                if (divergence == Divergence.CHANGED) changed.add(kit.path());
                else missing.add(kit.path());
                if (report(tenantId, projectId, kit, divergence)) {
                    reported.add(kit.path());
                }
            }
        }
        return new Report(List.copyOf(changed), List.copyOf(missing), List.copyOf(reported));
    }

    private enum Divergence { CHANGED, MISSING }

    private @Nullable Divergence classify(String tenantId, String projectId, DesiredKit kit) {
        KitInstalledRecordDto record =
                recordStore.findByOrigin(tenantId, projectId, kit.sourceUrl(), kit.path());
        if (record == null) {
            // Not here. Only worth saying when nobody has already granted the
            // source permission to fix it itself.
            return kit.authority().mayInstallNew() ? null : Divergence.MISSING;
        }
        if (kit.authority().mayUpdateInstalled()) {
            // The entry allows unattended refresh, so a difference is not a
            // question for a person — the update path deals with it.
            return null;
        }
        String now = KitProvisioningStamp.of(kit.revision(), kit.params());
        if (now == null) {
            // The source cannot state a revision. Nothing is checked rather
            // than guessed: guessing would refetch every tick or never.
            return null;
        }
        String installed = record.getOrigin() == null
                ? null : record.getOrigin().getProvisioningStamp();
        if (installed == null) {
            // Installed before the stamp existed, or by hand. Not a divergence:
            // announcing one for every such kit on the first tick after an
            // upgrade would be noise nobody asked for.
            return null;
        }
        return now.equals(installed) ? null : Divergence.CHANGED;
    }

    /**
     * Create the notice unless one is already open for this kit.
     *
     * @return true when an item was created
     */
    private boolean report(
            String tenantId, String projectId, DesiredKit kit, Divergence divergence) {

        String assignee = loader.declaredBy(tenantId, projectId);
        if (assignee == null || assignee.isBlank()) {
            // Nobody to tell. The person who wrote the entry is the person who
            // cares; without them the log is the honest place for this.
            log.info("Provisioning check of {}/{}: '{}' diverged ({}), but the provisioning"
                    + " document names no author to notify",
                    tenantId, projectId, kit.path(), divergence);
            return false;
        }
        String ref = reference(projectId, kit);
        if (hasOpenItem(tenantId, assignee, ref)) {
            log.debug("Provisioning check of {}/{}: '{}' already reported",
                    tenantId, projectId, kit.path());
            return false;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("kitPath", kit.path());
        payload.put("sourceUrl", kit.sourceUrl());
        payload.put("divergence", divergence.name().toLowerCase(Locale.ROOT));

        inboxItems.create(InboxItemDocument.builder()
                .tenantId(tenantId)
                .originatorUserId(KitProvisioningService.ACTOR)
                .assignedToUserId(assignee)
                .type(InboxItemType.DECISION)
                .criticality(Criticality.NORMAL)
                .tags(new ArrayList<>(List.of(TAG)))
                .title(title(kit, divergence))
                .body(body(projectId, kit, divergence))
                .payload(payload)
                .effectType(EFFECT_TYPE)
                .effectRef(ref)
                .requiresAction(true)
                .build());
        log.info("Provisioning check of {}/{}: reported '{}' ({})",
                tenantId, projectId, kit.path(), divergence);
        return true;
    }

    private boolean hasOpenItem(String tenantId, String assignee, String ref) {
        // Queried by tag and filtered on the reference in memory: the pending
        // set carrying this tag is small, and listFiltered has no effectRef
        // predicate worth adding for one caller.
        return inboxItems.listFiltered(tenantId, List.of(assignee), InboxItemStatus.PENDING, TAG)
                .stream()
                .anyMatch(item -> ref.equals(item.getEffectRef()));
    }

    /** Identity of the thing being reported — one open item per kit per project. */
    private static String reference(String projectId, DesiredKit kit) {
        return projectId + '|' + kit.sourceUrl() + '|' + kit.path();
    }

    private static String title(DesiredKit kit, Divergence divergence) {
        return divergence == Divergence.CHANGED
                ? "Kit '" + kit.path() + "' has changed at its source"
                : "Kit '" + kit.path() + "' is expected here but not installed";
    }

    private static String body(String projectId, DesiredKit kit, Divergence divergence) {
        String what = divergence == Divergence.CHANGED
                ? "The source reports different content than what is installed."
                : "The source lists this kit for this project, but it is not installed here.";
        return what + "\n\nProject: " + projectId
                + "\nSource: " + kit.sourceUrl()
                + "\nKit: " + kit.path()
                + "\n\nNothing has been changed. Run a kit update for this project to apply it,"
                + " or raise the entry's authority in _vance/kits/provisioning.yaml to let"
                + " the source do it unattended.";
    }
}
