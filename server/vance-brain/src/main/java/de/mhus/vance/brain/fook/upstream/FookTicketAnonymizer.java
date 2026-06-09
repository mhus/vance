package de.mhus.vance.brain.fook.upstream;

import de.mhus.vance.brain.fook.TicketDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Pure-logic anonymization + scrubbing of a {@link TicketDocument}
 * just before it's handed to a {@link TicketProvider}. Two
 * orthogonal jobs:
 *
 * <ol>
 *   <li><b>Identity hashing.</b> Reporter user-id and tenant-id
 *       become deterministic hashes (sha256 of value + instance
 *       secret). The upstream side can correlate "same reporter,
 *       three reports" without ever seeing the real identity.</li>
 *   <li><b>Content scrubbing.</b> Free-form text (title,
 *       description, triage note) is run through a configurable
 *       set of regex patterns: emails, IPv4 addresses, API-key
 *       shapes, GUIDs. Matches are replaced with a short
 *       redaction marker ({@code [redacted-email]} etc.) so the
 *       result still reads naturally.</li>
 * </ol>
 *
 * <p>Stateless component — all configuration is passed in
 * per-call. No setting reads here; that's
 * {@code FookUpstreamService}'s responsibility.
 */
@Component
@Slf4j
public class FookTicketAnonymizer {

    private static final List<ScrubPattern> ALL_PATTERNS = List.of(
            new ScrubPattern("email",
                    Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                    "[redacted-email]"),
            new ScrubPattern("ipv4",
                    Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"),
                    "[redacted-ip]"),
            new ScrubPattern("apiKey",
                    // Anthropic, OpenAI, GitHub-PAT, Slack-Bot shaped tokens.
                    Pattern.compile(
                            "\\b(?:sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{30,}|"
                                    + "xox[bpa]-[A-Za-z0-9-]{20,})"),
                    "[redacted-key]"),
            new ScrubPattern("guid",
                    Pattern.compile(
                            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
                    "[redacted-uuid]")
    );

    private static final Map<String, ScrubPattern> BY_NAME =
            ALL_PATTERNS.stream().collect(
                    LinkedHashMap::new,
                    (m, p) -> m.put(p.name, p),
                    LinkedHashMap::putAll);

    /** Names of built-in scrub patterns the operator can pick from. */
    public static List<String> availablePatternNames() {
        return ALL_PATTERNS.stream().map(p -> p.name).toList();
    }

    /**
     * Build a {@link ProviderTicketDraft} from a local ticket plus
     * the operator's anonymization config.
     *
     * @param ticket              the local ticket document
     * @param instanceSecret      brain-instance secret used to salt
     *                            identity hashes; must be stable across
     *                            sends so the upstream can correlate
     * @param instanceFingerprint short opaque identifier for this
     *                            brain-instance (visible to upstream)
     * @param anonymizeIdentity   when {@code false}, user/tenant are
     *                            stripped to empty strings instead of
     *                            hashed (privacy-maximalist mode)
     * @param scrubPatternNames   subset of {@link #availablePatternNames}
     *                            to apply to text fields
     * @param extraLabels         tenant-configured extra labels
     */
    public ProviderTicketDraft buildDraft(
            TicketDocument ticket,
            String instanceSecret,
            String instanceFingerprint,
            boolean anonymizeIdentity,
            List<String> scrubPatternNames,
            List<String> extraLabels) {

        List<ScrubPattern> active = resolvePatterns(scrubPatternNames);

        String reporterHash;
        if (anonymizeIdentity && ticket.getReporter() != null) {
            reporterHash = identityHash(
                    ticket.getReporter().getUserId(),
                    ticket.getReporter().getTenantId(),
                    instanceSecret);
        } else {
            reporterHash = "anonymous";
        }

        String body = buildBody(ticket, reporterHash, instanceFingerprint, active);

        return ProviderTicketDraft.builder()
                .title(scrub(ticket.getTitle(), active))
                .body(body)
                .type(ticket.getType())
                .severity(ticket.getSeverity())
                .extraLabels(extraLabels == null ? List.of() : extraLabels)
                .reporterHash(reporterHash)
                .instanceFingerprint(instanceFingerprint)
                .fookTicketId(ticket.getId())
                .providerHints(null)
                .build();
    }

    /**
     * Scrub a single reporter-reply text for posting back as a
     * comment. Identity isn't relevant here — providers add the
     * reporter-hash via the comment-body template.
     */
    public String scrubText(String text, List<String> scrubPatternNames) {
        return scrub(text, resolvePatterns(scrubPatternNames));
    }

    // ─── internals ──────────────────────────────────────────────────

    private String buildBody(
            TicketDocument ticket,
            String reporterHash,
            String instanceFingerprint,
            List<ScrubPattern> active) {
        StringBuilder b = new StringBuilder();
        b.append("> Auto-generated by Vance Fook from a user/engine support request.\n")
                .append("> Source: `").append(instanceFingerprint).append("`")
                .append(" · Reporter: `").append(reporterHash).append("`\n\n");

        b.append("## What was reported\n\n");
        b.append(scrub(ticket.getDescription(), active)).append("\n\n");

        if (ticket.getTriageNote() != null && !ticket.getTriageNote().isBlank()) {
            b.append("## Triage notes\n\n");
            b.append(scrub(ticket.getTriageNote(), active)).append("\n\n");
        }

        b.append("## Metadata\n\n");
        b.append("- type: ").append(safeOrDash(ticket.getType())).append("\n");
        b.append("- severity: ").append(safeOrDash(ticket.getSeverity())).append("\n");
        if (ticket.getContext() != null) {
            // Only the non-identifying fields — projectId/sessionId/processId
            // stay out of the upstream body.
            if (ticket.getContext().getRecipe() != null) {
                b.append("- recipe: ").append(ticket.getContext().getRecipe()).append("\n");
            }
            if (ticket.getContext().getEngine() != null) {
                b.append("- engine: ").append(ticket.getContext().getEngine()).append("\n");
            }
        }
        if (ticket.getTriagedAt() != null) {
            b.append("- triagedAt: ").append(ticket.getTriagedAt()).append("\n");
        }
        b.append("- fookTicketId: `").append(ticket.getId()).append("`\n");
        return b.toString();
    }

    private List<ScrubPattern> resolvePatterns(@Nullable List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        // Preserve operator order, drop duplicates, drop unknowns.
        LinkedHashSet<String> ordered = new LinkedHashSet<>(names);
        List<ScrubPattern> out = new ArrayList<>(ordered.size());
        for (String n : ordered) {
            ScrubPattern p = BY_NAME.get(n);
            if (p == null) {
                log.warn("Fook: unknown scrub pattern '{}' — ignored", n);
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static String scrub(@Nullable String input, List<ScrubPattern> patterns) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        String out = input;
        for (ScrubPattern p : patterns) {
            out = p.pattern.matcher(out).replaceAll(p.replacement);
        }
        return out;
    }

    private static String identityHash(
            @Nullable String userId,
            @Nullable String tenantId,
            String instanceSecret) {
        String input = safe(tenantId) + "|" + safe(userId) + "|" + instanceSecret;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // 16 hex chars = 64 bits of entropy — plenty for correlation,
            // useless for reversing.
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String safeOrDash(@Nullable String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    @Getter
    @AllArgsConstructor
    private static class ScrubPattern {
        private final String name;
        private final Pattern pattern;
        private final String replacement;
    }
}
