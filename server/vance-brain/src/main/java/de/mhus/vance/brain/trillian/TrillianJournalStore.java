package de.mhus.vance.brain.trillian;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The reflexion journal of one Trillian: an append-only markdown
 * document beside its attributes.
 *
 * <p><b>Why not in the attribute file.</b> The two look alike — both are
 * per-account documents that shape how the Trillian behaves — but they
 * have different owners. Attributes are what a human configured; the
 * journal is what the Trillian concluded about its own work. Sharing one
 * file would let the agent rewrite what the human set, and would make a
 * hand-edit race with the next reflexion.
 *
 * <p><b>Entries can be removed.</b> An append-only journal was the first
 * design, on the argument that an agent allowed to delete will eventually
 * delete the inconvenient entry unseen. That argument does not survive
 * contact with document versioning: a removed line is still in the
 * previous version, so nothing disappears unnoticed anyway. What
 * append-only does buy is a file that only grows — and every line of it
 * is rendered into a prompt on every turn. Since notes go stale (the
 * project changes) and are sometimes simply wrong, a journal without a
 * way back accumulates exactly the material nobody wants in a prompt.
 *
 * <p>Removal happens through the reflexion pass, which already has the
 * journal in front of it — so pruning occurs at the moment something new
 * is learned, which is also the moment an old note is noticed to be
 * obsolete.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianJournalStore {

    /** A leading {@code YYYY-MM-DD:} — already stamped. */
    private static final Pattern DATED = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}:");

    private static final String FOLDER = "_vance/trillian/";
    private static final String DOC_TITLE_PREFIX = "Trillian journal — ";
    private static final List<String> TAGS = List.of("trillian", "journal");

    /**
     * How much of the journal is rendered into the prompt. Kept well
     * below the attribute block's weight: the journal grows with every
     * task, and an agent that spends its context re-reading its own diary
     * has no room left to work.
     */
    public static final int PROMPT_BUDGET_CHARS = 4_000;

    /** Package-private so the test can match on it instead of copying it. */
    static final String HEADER = """
            # Trillian journal

            What this Trillian concluded after finishing tasks. Written by the
            Trillian itself, newest entries at the bottom, each stamped with the
            date it was written. Editing and deleting by hand is fine — earlier
            versions of this document keep the history.
            """;

    private final DocumentService documentService;

    /** Per-journal monitors — see {@link #append}. */
    private final java.util.concurrent.ConcurrentMap<String, Object> locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Document path for an account, exposed for tests and log lines. */
    public static String pathFor(String account) {
        return FOLDER + account + ".journal.md";
    }

    /**
     * Appends one entry. Best-effort — a lost reflexion costs a lesson,
     * and it must not cost the task result that triggered it.
     *
     * <p><b>Serialised per journal.</b> The append is a read-modify-write
     * over the whole document, and the reflexion that produces an entry
     * runs off the reporting thread — two tasks concluding at once would
     * otherwise both read the old body and the second write would drop
     * the first lesson. Silently: an append-only record that loses
     * entries is worse than one that never existed, because it is still
     * believed. The lock is pod-local, which covers the real case (a
     * Trillian's worker session lives on one pod); a genuinely
     * cross-pod race would still need document-level compare-and-set.
     */
    public void append(String tenantId, String projectId, String account, String entry) {
        if (entry.isBlank()) {
            return;
        }
        synchronized (lockFor(tenantId, projectId, account)) {
            try {
                String stamped = stamp(entry.strip());
                String existing = readText(tenantId, projectId, account);
                String body = existing == null || existing.isBlank()
                        ? HEADER + "\n" + stamped + "\n"
                        : existing.stripTrailing() + "\n" + stamped + "\n";
                write(tenantId, projectId, account, body);
            } catch (RuntimeException e) {
                log.warn("Trillian: could not append to journal of '{}': {}",
                        account, e.toString());
            }
        }
    }

    /**
     * One monitor per journal. Keyed by the full path, so two accounts —
     * or the same name in two projects — never wait on each other. The map
     * grows with the number of Trillians that ever reflected on this pod,
     * which is the same order as the number of accounts.
     */
    private Object lockFor(String tenantId, String projectId, String account) {
        return locks.computeIfAbsent(
                tenantId + "/" + projectId + "/" + account, k -> new Object());
    }

    /**
     * The tail of the journal, capped at {@link #PROMPT_BUDGET_CHARS} and
     * cut at an entry boundary. Returns {@code null} when there is
     * nothing to show, so callers can omit the section entirely rather
     * than render an empty heading.
     */
    public @Nullable String tail(String tenantId, String projectId, String account) {
        // body() drops the self-describing header: it explains the file
        // to a human opening it and says nothing to the Trillian. Matched
        // as a prefix rather than parsed, so editing HEADER cannot
        // silently start leaking it into the prompt.
        String body = body(readText(tenantId, projectId, account));
        if (body == null) {
            return null;
        }
        if (body.length() <= PROMPT_BUDGET_CHARS) {
            return body;
        }
        String cut = body.substring(body.length() - PROMPT_BUDGET_CHARS);
        int nl = cut.indexOf('\n');
        return (nl >= 0 ? cut.substring(nl + 1) : cut).strip();
    }

    /**
     * The individual entries, oldest first — one per line, each starting
     * with {@code "- "}. This is the list the reflexion pass sees
     * numbered, so its {@code remove} indices refer to exactly these
     * positions.
     */
    public List<String> entries(String tenantId, String projectId, String account) {
        String body = body(readText(tenantId, projectId, account));
        if (body == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ")) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Drops the entries at the given 1-based positions and rewrites the
     * document.
     *
     * <p>Out-of-range positions are ignored rather than rejected: the
     * caller is an LLM working from a numbered list, and a stray index
     * should cost one skipped removal, not the whole write.
     */
    public void removeEntries(String tenantId, String projectId, String account,
            Collection<Integer> positions) {
        if (positions.isEmpty()) {
            return;
        }
        // Same monitor as append: a prune and an append in the same
        // reflexion must not read the same file and write it twice.
        synchronized (lockFor(tenantId, projectId, account)) {
            try {
                List<String> entries = entries(tenantId, projectId, account);
                if (entries.isEmpty()) {
                    return;
                }
                Set<Integer> drop = new HashSet<>(positions);
                List<String> kept = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    if (!drop.contains(i + 1)) {
                        kept.add(entries.get(i));
                    }
                }
                if (kept.size() == entries.size()) {
                    return;
                }
                write(tenantId, projectId, account,
                        HEADER + "\n" + String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"));
                log.info("Trillian: removed {} obsolete journal entr(y/ies) of '{}'",
                        entries.size() - kept.size(), account);
            } catch (RuntimeException e) {
                log.warn("Trillian: could not prune journal of '{}': {}",
                        account, e.toString());
            }
        }
    }

    /**
     * Prefixes the entry with the date it was written.
     *
     * <p>Stamped here rather than asked of the model: the reflexion pass
     * has no reliable notion of today, and an invented date is worse than
     * none — it would make a stale note look fresh. Deterministic at the
     * point of writing, so it is simply true.
     *
     * <p>The date is what lets a reader tell a standing fact from a
     * state that may have moved since. UTC, matching the date the engines
     * put in their prompts.
     */
    private static String stamp(String entry) {
        String text = entry.startsWith("- ") ? entry.substring(2).strip() : entry;
        // Idempotent: a model that imitated the format and wrote its own
        // date must not end up with two.
        if (DATED.matcher(text).find()) {
            return "- " + text;
        }
        return "- " + LocalDate.now(ZoneOffset.UTC) + ": " + text;
    }

    private void write(String tenantId, String projectId, String account, String body) {
        documentService.upsertText(
                tenantId, projectId, pathFor(account),
                DOC_TITLE_PREFIX + account, TAGS, body,
                /*createdBy*/ account,
                WriteActor.SYSTEM);
    }

    /** The entry part of the file, header stripped; {@code null} when empty. */
    private @Nullable String body(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped =
                (text.startsWith(HEADER) ? text.substring(HEADER.length()) : text).strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Removes the journal — the account it belongs to is going away. */
    public void discard(String tenantId, String projectId, String account) {
        try {
            documentService.findByPath(tenantId, projectId, pathFor(account))
                    .ifPresent(doc -> {
                        documentService.delete(doc.getId(), WriteActor.SYSTEM);
                        log.info("Trillian: removed journal {}", pathFor(account));
                    });
        } catch (RuntimeException e) {
            log.warn("Trillian: could not remove journal of '{}': {}", account, e.toString());
        }
    }

    private @Nullable String readText(String tenantId, String projectId, String account) {
        try {
            Optional<DocumentDocument> doc =
                    documentService.findByPath(tenantId, projectId, pathFor(account));
            return doc.map(documentService::readContent).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Trillian: could not read journal of '{}': {}", account, e.toString());
            return null;
        }
    }
}
