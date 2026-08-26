package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * "You cannot open this app — but you can ask."
 *
 * <p>With {@code forbidden} as the default, a tenant that has never written
 * {@code applications.yaml} would otherwise be a dead end: a message pointing at
 * a document the reader is not allowed to write. This turns it into a request,
 * and answering it **creates** the configuration — which is why the opt-in
 * default is not a broken tenant, just an unanswered question.
 *
 * <p><b>What the app asks for, it already says.</b> The proposal is the app's own
 * {@code custom.rest} declaration. No new syntax, and the field that was
 * documentation and a signature anchor now has a third use.
 *
 * <p><b>The proposal is frozen.</b> It is stored with the request, not re-read at
 * approval time — an app that widened its declaration between asking and being
 * answered must not be approved for the wider thing. The {@code InboxEffect}
 * contract says the same in general terms: what the deciding UI shows must come
 * from the effect's own storage, never from text the requester controls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppReleaseRequestService {

    private final ApplicationsPolicyService policyService;
    private final AppGrantStore grants;
    private final BistromathStore store;
    private final MaximegalonService inbox;

    /** What happened, for the client to render. */
    public record Receipt(String status, @Nullable String inboxItemId, String message) {}

    /**
     * Whether asking is possible, for the client to decide what to offer.
     *
     * <p>A separate route rather than a flag squeezed into the refusal: the
     * client would otherwise have to read the 403's prose to know whether a
     * button belongs there, and matching on a message is the thing that breaks
     * the day the wording improves.
     */
    public record Status(String mode, boolean canRequest, boolean pending,
                         @Nullable String inboxItemId, @Nullable String reason) {}

    public Status status(String tenantId, String projectId, String folder) {
        AppPolicy policy = policyService.resolve(tenantId, projectId, folder);
        String mode = policy.mode().name();
        if (policy.mode() == AppMode.ALLOWED) {
            return new Status(mode, false, false, null, null);
        }
        ApplicationsConfig config = policyService.configuration(tenantId);
        if (config.explicitAppRule(projectId, folder) != null) {
            // The admin already answered by naming this app in their own file,
            // and that file wins over any grant. Offering to ask would produce
            // a decision that changes nothing — an approval whose effect is
            // silently overridden is worse than no button.
            return new Status(mode, false, false, null,
                    "An admin has set this application's rule explicitly in"
                            + " _vance/config/applications.yaml. Asking again would not"
                            + " change it — talk to them.");
        }
        if (config.requestsTo() == null) {
            return new Status(mode, false, false, null,
                    "This tenant has not set up a release path. An admin adds"
                            + " `requests: { to: <user> }` to _vance/config/applications.yaml.");
        }
        AppGrantRecord record = grants.find(
                tenantId, ApplicationsConfig.appKey(projectId, folder));
        if (record != null && record.open()) {
            return new Status(mode, false, true, record.inboxItemId(),
                    "A request for this app is waiting for a decision.");
        }
        if (record != null && record.status() == AppGrantRecord.Status.DENIED) {
            return new Status(mode, false, false, record.inboxItemId(),
                    "Release was refused for this app.");
        }
        return new Status(mode, true, false, null, null);
    }

    /**
     * Raise a request, or hand back the open one.
     *
     * @param requestedBy the reader asking — never the app, and never SYSTEM
     */
    public Receipt request(String tenantId, String projectId, String folder,
                          String requestedBy, @Nullable String reason) {
        String appKey = ApplicationsConfig.appKey(projectId, folder);
        ApplicationsConfig config = policyService.configuration(tenantId);

        if (config.explicitAppRule(projectId, folder) != null) {
            // Checked here as well as in status(): the client is not the only
            // caller, and a request whose approval cannot take effect should
            // never be created in the first place.
            throw new ToolException("An admin has set this application's rule explicitly."
                    + " A decision here would not change it.");
        }

        String decider = config.requestsTo();
        if (decider == null) {
            // No addressee, no request. Said plainly rather than queued for
            // nobody: the reader should go and find an admin, and knowing that
            // is more useful than a receipt for a message no one receives.
            throw new ToolException("This tenant has not set up a release path for custom"
                    + " applications. An admin adds `requests: { to: <user> }` to"
                    + " _vance/config/applications.yaml.");
        }

        AppGrantRecord existing = grants.find(tenantId, appKey);
        if (existing != null && existing.open()) {
            // One open request per app. A second click attaches to the first
            // rather than adding a second thread — a Maximegalon thread holds at
            // most one decision, and two threads for one question is how an
            // admin learns to ignore them.
            return new Receipt("PENDING", existing.inboxItemId(),
                    "A request for this app is already waiting for a decision.");
        }
        if (existing != null && existing.status() == AppGrantRecord.Status.DENIED) {
            // Kept rather than cleared, so a refusal is not a round trip away
            // from being asked again. Lifting it is an admin edit.
            throw new ToolException("This app was refused release. Ask the admin directly"
                    + " rather than repeating the request.");
        }

        AppPolicy proposal = proposalFor(tenantId, projectId, folder);
        String now = Instant.now().toString();

        MaximegalonDocument item = inbox.create(MaximegalonDocument.builder()
                .tenantId(tenantId)
                .originatorUserId(requestedBy)
                .assignedToUserId(decider)
                .type(MaximegalonType.APPROVAL)
                // Never LOW: a LOW item carrying a default auto-answers, and
                // running somebody's code in other people's browsers must not be
                // decided by a default. Same reasoning as the permission request.
                .criticality(Criticality.CRITICAL)
                .title("Release custom application '" + folder + "'")
                .body(body(projectId, folder, requestedBy, proposal, reason))
                .effectType(AppReleaseEffect.EFFECT_TYPE)
                .effectRef(appKey)
                .requiresAction(true)
                .build());

        grants.put(tenantId, appKey, new AppGrantRecord(
                        AppGrantRecord.Status.REQUESTED,
                        proposal.mode(), proposal.restFamilies(),
                        proposal.surface(), proposal.documentsWritable(),
                        requestedBy, now, item.getId(), null, null),
                WriteActor.SYSTEM);

        log.info("App release requested tenant='{}' app='{}' by='{}' decider='{}' item='{}'",
                tenantId, appKey, requestedBy, decider, item.getId());
        return new Receipt("PENDING", item.getId(),
                "Sent to " + decider + " for a decision.");
    }

    /**
     * What to ask for: the app's own declaration, as `restricted`.
     *
     * <p>Never `allowed`: a request should ask for what the app says it needs,
     * and "everything" is not something an app declared. An admin who wants to
     * hand it more can edit the file — the request is a proposal, not a menu.
     */
    private AppPolicy proposalFor(String tenantId, String projectId, String folder) {
        BistromathStore.Loaded loaded = store.load(tenantId, projectId, folder);
        List<String> declared = BistromathConfig.from(loaded.manifestDoc()).rest();
        return new AppPolicy(AppMode.RESTRICTED,
                declared == null ? List.of() : declared,
                // The two capability levers keep their restricted defaults: a
                // surface is asked for, its own data is not taken away.
                false, true);
    }

    private static String body(String projectId, String folder, String requestedBy,
                               AppPolicy proposal, @Nullable String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append(requestedBy).append(" would like to open the custom application `")
                .append(folder).append("` in project `").append(projectId).append("`.\n\n");
        if (reason != null && !reason.isBlank()) {
            sb.append("Reason given: ").append(reason.trim()).append("\n\n");
        }
        sb.append("Approving records it as `restricted` with the routes the app declares");
        List<String> rest = proposal.restFamilies();
        sb.append(rest == null || rest.isEmpty()
                ? " — which is none, so it can only use its own documents.\n"
                : ": " + String.join(", ", rest) + ".\n");
        sb.append("\nThe exact effect is shown beside this item, taken from the request"
                + " rather than from this text.");
        return sb.toString();
    }
}
