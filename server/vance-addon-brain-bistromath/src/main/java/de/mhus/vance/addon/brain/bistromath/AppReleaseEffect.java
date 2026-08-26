package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.EffectDescription;
import de.mhus.vance.api.inbox.EffectFact;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.InboxEffect;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.permission.WriteActor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What approving or refusing a release request does.
 *
 * <p>An {@link InboxEffect} contributed by this addon — the same shape
 * {@code PermissionRequestEffect} uses in the Simple-Auth addon, which is why
 * none of this needs a change in the core.
 *
 * <p>The mutation is a line in {@link AppGrantStore}, written under the
 * **approver's** identity: they are the admin whose decision it is, and a write
 * that names nobody is a write nobody can be asked about.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppReleaseEffect implements InboxEffect {

    /** Persisted on items that may outlive a release, so it never changes. */
    public static final String EFFECT_TYPE = "bistromath-app-release";

    private final AppGrantStore grants;
    private final SecurityContextFactory contextFactory;

    @Override
    public String effectType() {
        return EFFECT_TYPE;
    }

    @Override
    public void onApproved(MaximegalonDocument item, AnswerPayload answer) {
        settle(item, answer, AppGrantRecord.Status.GRANTED);
    }

    @Override
    public void onRejected(MaximegalonDocument item, AnswerPayload answer) {
        // Recorded, not deleted: a refusal that left no trace would be one
        // click away from being asked again.
        settle(item, answer, AppGrantRecord.Status.DENIED);
    }

    /**
     * Facts for the deciding UI, **from the request** rather than from the
     * item's text.
     *
     * <p>The SPI is explicit about this and the reason is worth repeating: the
     * body was written for a human to read, and letting it describe its own
     * effect hands the description to whoever asked.
     */
    @Override
    public Optional<EffectDescription> describe(MaximegalonDocument item) {
        AppGrantRecord record = record(item);
        if (record == null) return Optional.empty();

        List<EffectFact> facts = new ArrayList<>();
        facts.add(new EffectFact("Application", item.getEffectRef() == null
                ? "?" : item.getEffectRef()));
        facts.add(new EffectFact("Mode", record.mode().name().toLowerCase()));
        List<String> rest = record.restFamilies();
        facts.add(new EffectFact("REST routes",
                rest == null || rest.isEmpty() ? "none — its own documents only"
                        : String.join(", ", rest)));
        facts.add(new EffectFact("Own drawing surface", record.surface() ? "yes" : "no"));
        facts.add(new EffectFact("Documents",
                record.documentsWritable() ? "read and write" : "read only"));
        if (record.requestedBy() != null) {
            facts.add(new EffectFact("Requested by", record.requestedBy()));
        }
        return Optional.of(new EffectDescription(
                record.status().name().toLowerCase(),
                "Approving writes this into " + AppGrantStore.PATH
                        + ". The hand-written applications.yaml still wins where it names"
                        + " the app.",
                facts));
    }

    private void settle(MaximegalonDocument item, AnswerPayload answer,
                        AppGrantRecord.Status status) {
        String appKey = item.getEffectRef();
        if (appKey == null || appKey.isBlank()) {
            log.warn("App release item '{}' has no effectRef — nothing to settle", item.getId());
            return;
        }
        AppGrantRecord record = record(item);
        if (record == null) {
            // The request is gone (an admin edited the document, or the tenant
            // was cleaned up). The human decision is not discarded — the item
            // stays answered — but there is nothing left to apply.
            log.warn("App release item '{}' has no record for '{}' — nothing to settle",
                    item.getId(), appKey);
            return;
        }
        if (!record.open()) {
            // Idempotent: a replayed answer must not turn a refusal into a grant.
            log.debug("App release '{}' already {} — ignoring", appKey, record.status());
            return;
        }

        String decidedBy = answer.getAnsweredBy();
        grants.put(item.getTenantId(), appKey,
                new AppGrantRecord(status, record.mode(), record.restFamilies(),
                        record.surface(), record.documentsWritable(),
                        record.requestedBy(), record.requestedAt(), record.inboxItemId(),
                        decidedBy, Instant.now().toString()),
                WriteActor.system(contextFactory.forToolSubject(item.getTenantId(), decidedBy)));

        log.info("App release {} tenant='{}' app='{}' by='{}'",
                status, item.getTenantId(), appKey, decidedBy);
    }

    private @Nullable AppGrantRecord record(MaximegalonDocument item) {
        String appKey = item.getEffectRef();
        if (appKey == null || appKey.isBlank()) return null;
        try {
            return grants.find(item.getTenantId(), appKey);
        } catch (RuntimeException e) {
            // A malformed grants document must not take the deciding UI down
            // with it — the item still renders, just without its facts.
            log.warn("Cannot read app release record for '{}': {}", appKey, e.toString());
            return null;
        }
    }
}
