package de.mhus.vance.brain.usermemory;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Cross-engine per-user memory store. Two artifacts per user:
 *
 * <ul>
 *   <li><b>persona</b> ({@link #PERSONA_DOC_PATH}) — freeform summary
 *       of how the assistant should talk to this user. Always loaded
 *       into the engine prompt. Mode controls whether new content
 *       replaces (default) or appends.</li>
 *   <li><b>facts</b> ({@link #FACTS_DOC_PATH}) — append-only journal
 *       of factual entries (birthday, preferences, dislikes). Each
 *       entry is date-stamped on write.</li>
 * </ul>
 *
 * <p>Both files live in the user's hub project ({@code _user_<login>}).
 * Eddie's process runs in that project already, so {@code projectId ==
 * userProjectName}. Arthur's process runs in a work project — callers
 * resolve the user-project name via
 * {@link de.mhus.vance.shared.home.HomeBootstrapService#hubProjectName(String)}
 * and pass it explicitly. The service never derives the project on its
 * own; making it a required parameter keeps the engine in control of
 * which user the memory belongs to.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserMemoryService {

    /** Document path for the persona-summary inside the user's hub project. */
    public static final String PERSONA_DOC_PATH = "user/persona.md";

    /** Document path for the append-only facts journal inside the user's hub project. */
    public static final String FACTS_DOC_PATH = "user/facts.md";

    /** Soft limit on the size of the facts file rendered into the prompt. */
    public static final int FACTS_PROMPT_BUDGET_CHARS = 8_000;

    public static final String SCOPE_PERSONA = "persona";
    public static final String SCOPE_FACT    = "fact";
    public static final Set<String> SCOPES   = Set.of(SCOPE_PERSONA, SCOPE_FACT);

    public static final String MODE_APPEND   = "append";
    public static final String MODE_REPLACE  = "replace";

    private static final String PERSONA_DOC_TITLE = "User persona summary";
    private static final String FACTS_DOC_TITLE   = "User facts journal";
    private static final List<String> PERSONA_TAGS = List.of("user", "persona");
    private static final List<String> FACTS_TAGS   = List.of("user", "facts");

    private final DocumentService documentService;

    // ──────────────────── Read ────────────────────

    /** Returns the raw persona text or {@code null} if no persona was ever learned. */
    public @Nullable String loadPersona(String tenantId, String userProjectName) {
        return readDocText(tenantId, userProjectName, PERSONA_DOC_PATH);
    }

    /** Returns the raw facts journal or {@code null} if empty. */
    public @Nullable String loadFacts(String tenantId, String userProjectName) {
        return readDocText(tenantId, userProjectName, FACTS_DOC_PATH);
    }

    /**
     * Loads the persona document and wraps it in a "## How to talk to
     * this user" block. Returns {@code null} when no persona exists
     * yet — the engine simply omits the section in that case.
     */
    public @Nullable String composePersonaBlock(String tenantId, String userProjectName) {
        String text = loadPersona(tenantId, userProjectName);
        if (text == null || text.isBlank()) {
            return null;
        }
        return "## How to talk to this user\n\n"
                + text.trim()
                + "\n\n_Maintain this via `LEARN scope=persona`._";
    }

    /**
     * Loads the facts document, truncates the head to
     * {@link #FACTS_PROMPT_BUDGET_CHARS} (newest entries kept), and
     * wraps it in a "## What I know about this user" block. Returns
     * {@code null} when no facts exist.
     */
    public @Nullable String composeFactsBlock(String tenantId, String userProjectName) {
        String text = loadFacts(tenantId, userProjectName);
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.stripTrailing();
        if (trimmed.length() > FACTS_PROMPT_BUDGET_CHARS) {
            int from = trimmed.length() - FACTS_PROMPT_BUDGET_CHARS;
            // Snap to the next newline so we don't slice a fact in half.
            int nl = trimmed.indexOf('\n', from);
            trimmed = (nl >= 0 ? trimmed.substring(nl + 1) : trimmed.substring(from))
                    + "\n\n_(older entries omitted — consider consolidating into persona)_";
        }
        return "## What I know about this user\n\n"
                + trimmed
                + "\n\n_Append new facts via `LEARN scope=fact`._";
    }

    // ──────────────────── Write ────────────────────

    /**
     * Replaces or appends to the persona-summary, depending on
     * {@code mode}. Returns the new total length in chars.
     */
    public int learnPersona(
            String tenantId, String userProjectName,
            String content, @Nullable String mode, String authorTag) {
        String newContent = MODE_APPEND.equals(mode)
                ? mergeAppend(loadPersona(tenantId, userProjectName), content)
                : content.trim();
        documentService.upsertText(tenantId, userProjectName, PERSONA_DOC_PATH,
                PERSONA_DOC_TITLE, PERSONA_TAGS, newContent, authorTag);
        return newContent.length();
    }

    /**
     * Appends a date-stamped fact entry to the facts journal. Returns
     * the new total length in chars.
     */
    public int learnFact(
            String tenantId, String userProjectName,
            String content, String authorTag) {
        String today = LocalDate.now().toString();
        String entry = "[" + today + "] " + content.trim();
        String existing = loadFacts(tenantId, userProjectName);
        String newContent = (existing == null || existing.isBlank())
                ? entry
                : existing.stripTrailing() + "\n" + entry;
        documentService.upsertText(tenantId, userProjectName, FACTS_DOC_PATH,
                FACTS_DOC_TITLE, FACTS_TAGS, newContent, authorTag);
        return newContent.length();
    }

    // ──────────────────── Consolidation ────────────────────

    /**
     * Callback the engine supplies to perform the actual LLM call for
     * post-LEARN consolidation. Returns the consolidated text or
     * {@code null} on failure / empty reply. Keeps the service free
     * of LangChain4j / streaming-engine internals.
     */
    @FunctionalInterface
    public interface LlmConsolidator {
        @Nullable String consolidate(String systemPrompt, String currentText);
    }

    /**
     * Reads the just-written persona / facts file, hands it to the
     * caller's {@link LlmConsolidator} together with the canonical
     * consolidation system prompt, and writes the result back if it
     * differs. Skips the LLM call when the doc is too small to need
     * consolidation. Failures are logged and non-fatal — the raw
     * content stays on disk.
     */
    public void runConsolidation(
            String scope, String tenantId, String userProjectName,
            String authorTag, LlmConsolidator llm) {
        if (!SCOPES.contains(scope)) {
            return;
        }
        String docPath;
        String title;
        List<String> tags;
        String systemPrompt;
        switch (scope) {
            case SCOPE_PERSONA -> {
                docPath = PERSONA_DOC_PATH;
                title = PERSONA_DOC_TITLE;
                tags = PERSONA_TAGS;
                systemPrompt = PERSONA_CONSOLIDATE_SYSTEM;
            }
            case SCOPE_FACT -> {
                docPath = FACTS_DOC_PATH;
                title = FACTS_DOC_TITLE;
                tags = FACTS_TAGS;
                systemPrompt = FACTS_CONSOLIDATE_SYSTEM;
            }
            default -> { return; }
        }

        String current = readDocText(tenantId, userProjectName, docPath);
        if (current == null || current.isBlank()) {
            return;
        }
        String trimmed = current.trim();
        // Skip when there's nothing meaningful to consolidate (single
        // line / single fact). A real second entry crosses this easily.
        if (trimmed.lines().count() < 2 && trimmed.length() < 80) {
            return;
        }

        String consolidated;
        try {
            consolidated = llm.consolidate(systemPrompt, current);
        } catch (RuntimeException e) {
            log.warn("UserMemory consolidation scope='{}' project='{}' llm-call failed: {}",
                    scope, userProjectName, e.toString());
            return;
        }
        if (consolidated == null || consolidated.isBlank()) {
            log.warn("UserMemory consolidation scope='{}' project='{}' produced no text — keeping raw",
                    scope, userProjectName);
            return;
        }
        consolidated = stripFences(consolidated.trim());
        if (consolidated.equals(trimmed)) {
            log.debug("UserMemory consolidation scope='{}' project='{}': no changes",
                    scope, userProjectName);
            return;
        }
        documentService.upsertText(tenantId, userProjectName, docPath,
                title, tags, consolidated, authorTag);
        log.info("UserMemory consolidation scope='{}' project='{}' {} → {} chars",
                scope, userProjectName, current.length(), consolidated.length());
    }

    // ──────────────────── Internals ────────────────────

    private @Nullable String readDocText(
            String tenantId, String projectId, String path) {
        Optional<DocumentDocument> docOpt =
                documentService.findByPath(tenantId, projectId, path);
        if (docOpt.isEmpty()) {
            return null;
        }
        // Persona + facts are small text on purpose — the prompt budget
        // keeps them well under any reasonable size. readContent streams
        // through storage transparently.
        return documentService.readContent(docOpt.get());
    }

    private static String mergeAppend(@Nullable String existing, String newPart) {
        if (existing == null || existing.isBlank()) {
            return newPart.trim();
        }
        return existing.stripTrailing() + "\n\n" + newPart.trim();
    }

    /**
     * Strips an outer Markdown code-fence (` ``` `) if the LLM wrapped
     * its output despite being told not to. Defensive — some models
     * habitually fence "this is text" responses.
     */
    private static String stripFences(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) return t;
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) return t;
        String inner = t.substring(firstNl + 1);
        if (inner.endsWith("```")) {
            inner = inner.substring(0, inner.length() - 3);
        }
        return inner.stripTrailing();
    }

    private static final String PERSONA_CONSOLIDATE_SYSTEM = """
            You are a persona-consolidator for an AI assistant. The text below is a
            free-form description of how the assistant should talk to a specific user.
            Over time it accumulates: instructions get added, sometimes contradicted,
            sometimes refined. Your job is to produce a clean, current-state version.

            Rules:
            - Resolve contradictions: the LATER instruction wins. If "be sarcastic"
              comes after "be polite", drop "be polite".
            - Merge complementary instructions ("be concise" + "no bullet lists" →
              "be concise; prose over lists").
            - Drop redundant phrasing.
            - Keep it short — five to ten sentences max. The assistant reads this
              every turn; bloat hurts everything.
            - Preserve the user's voice. If they said "wie Douglas Adams", keep that
              exact reference. Don't substitute generic synonyms.
            - Match the language of the input (German stays German).

            Output ONLY the consolidated persona text. No preamble, no explanation,
            no Markdown headers, no code fences.
            """;

    private static final String FACTS_CONSOLIDATE_SYSTEM = """
            You are a fact-consolidator for an AI assistant's per-user memory.
            Below is a journal of date-stamped facts about a specific user. Over
            time the same topic may be re-stated with new values (favourite color
            changed, address updated, preference flipped). Your job is to produce
            a clean current-state list.

            Rules:
            - For each topic (favourite color, birthday, dislike, role, …), keep
              ONLY the most recent entry. Drop superseded older versions.
            - Preserve each kept entry's original date stamp and wording.
            - Don't merge across distinct topics. Don't invent new facts.
            - Don't add or remove date stamps. Don't reformat dates.
            - Order chronologically, oldest first.
            - One fact per line.

            Output ONLY the consolidated list. No preamble, no explanation, no
            Markdown headers, no code fences.
            """;
}
