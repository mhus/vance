package de.mhus.vance.brain.trillian;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import java.util.List;
import java.util.Optional;
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
 * <p>Append-only for the same reason a fact journal is: a Trillian that
 * may rewrite its history can quietly erase the entry that would have
 * stopped it repeating a mistake.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianJournalStore {

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

    private static final String HEADER = """
            # Trillian journal

            What this Trillian concluded after finishing tasks. Written by the
            Trillian itself, newest entries at the bottom. Read-only in practice —
            editing is possible but the value of a journal is that it was not
            rewritten afterwards.
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
                String existing = readText(tenantId, projectId, account);
                String body = existing == null || existing.isBlank()
                        ? HEADER + "\n" + entry.strip() + "\n"
                        : existing.stripTrailing() + "\n" + entry.strip() + "\n";
                documentService.upsertText(
                        tenantId, projectId, pathFor(account),
                        DOC_TITLE_PREFIX + account, TAGS, body,
                        /*createdBy*/ account,
                        WriteActor.SYSTEM);
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
        String text = readText(tenantId, projectId, account);
        if (text == null || text.isBlank()) {
            return null;
        }
        // Drop the self-describing header: it explains the file to a
        // human opening it and says nothing to the Trillian. Matched as a
        // prefix rather than parsed, so editing HEADER cannot silently
        // start leaking it into the prompt.
        String body = (text.startsWith(HEADER) ? text.substring(HEADER.length()) : text).strip();
        if (body.isEmpty()) {
            return null;
        }
        if (body.length() <= PROMPT_BUDGET_CHARS) {
            return body;
        }
        String cut = body.substring(body.length() - PROMPT_BUDGET_CHARS);
        int nl = cut.indexOf('\n');
        return (nl >= 0 ? cut.substring(nl + 1) : cut).strip();
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
