package de.mhus.vance.brain.fook.upstream;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Anonymized payload handed to a {@link TicketProvider#create} call.
 * The reporter's userId, tenantId and project/session/process IDs
 * have already been stripped or hashed by {@code FookTicketAnonymizer}
 * — providers don't see PII.
 *
 * <p>The draft's fields use GitHub-flavoured naming because Fook is
 * GitHub-first; other providers must map these onto their native
 * concepts (e.g. GitLab issue labels = same idea; Jira issue
 * priority + labels = combined).
 */
@Value
@Builder
public class ProviderTicketDraft {

    /** Title for the external ticket (e.g. GitHub issue title). */
    String title;

    /**
     * Markdown body. Already anonymized + scrubbed. Includes the
     * original report text, the LLM's triage note, and metadata
     * footer (reporter-hash, instance-fingerprint, recipe, engine,
     * timestamps).
     */
    String body;

    /**
     * Fook-derived type: {@code bug}, {@code feature}, {@code question},
     * {@code other}. Providers translate into native labels.
     */
    String type;

    /**
     * Fook-derived severity: {@code low}, {@code medium}, {@code high}.
     * Providers translate into native labels / priority.
     */
    String severity;

    /**
     * Additional labels to apply (tenant-configured extras such as
     * deployment-name or environment tag). Provider may merge these
     * with its own derived labels.
     */
    List<String> extraLabels;

    /**
     * Opaque reporter identity — sha256-derived hash that lets the
     * provider correlate "same reporter, multiple reports" without
     * exposing the actual userId.
     */
    String reporterHash;

    /**
     * Opaque source-instance fingerprint — identifies the Brain that
     * sent this ticket. Multiple submissions from the same tenant
     * carry the same fingerprint.
     */
    String instanceFingerprint;

    /**
     * Stable Fook-side UUID. Providers may store it as a label or
     * footer reference so the reverse direction (poll-updates) can
     * disambiguate.
     */
    String fookTicketId;

    /**
     * Provider-specific tweaks. Reserved for future use (e.g. project
     * board column, milestone). v1: ignored by GitHubTicketProvider.
     */
    @Nullable Map<String, Object> providerHints;
}
