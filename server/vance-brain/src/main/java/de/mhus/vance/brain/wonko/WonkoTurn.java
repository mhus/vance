package de.mhus.vance.brain.wonko;

import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the item handlers of one lane turn share: the process, its state and
 * register, and the documents read during the turn (read once per turn
 * through the permission-checked {@code doc_read}, never persisted in state).
 */
final class WonkoTurn {

    final ThinkProcessDocument process;
    final ThinkEngineContext ctx;
    final WonkoState state;
    final WonkoRegister register;
    final WonkoParams params;
    final String goal;
    final @Nullable String userId;

    /** The {@code ## Languages} block of the process's settings — every model call gets it. */
    final String languageBlock;

    /** Bound on the decision log of one run — it explains, it is not an audit trail of every call. */
    private static final int MAX_DECISIONS = 300;

    private final Map<String, String> docs = new LinkedHashMap<>();

    WonkoTurn(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            WonkoState state,
            WonkoRegister register,
            WonkoParams params,
            String goal,
            @Nullable String userId,
            String languageBlock) {
        this.process = process;
        this.ctx = ctx;
        this.state = state;
        this.register = register;
        this.params = params;
        this.goal = goal;
        this.userId = userId;
        this.languageBlock = languageBlock;
    }

    /** Document text by path, read on first use; {@code null} when unreadable. */
    @Nullable
    String doc(String path) {
        if (docs.containsKey(path)) {
            return docs.get(path);
        }
        String text = null;
        try {
            Object content =
                    ctx.tools().invoke("doc_read", Map.of("path", path)).get("content");
            text = content instanceof String s ? s : null;
        } catch (RuntimeException e) {
            log(path, "unreadable", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        docs.put(path, text);
        return text;
    }

    /** All reference documents that could be read, path → text. */
    Map<String, String> references() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : state.getReferencePaths()) {
            String text = doc(path);
            if (text != null) {
                out.put(path, text);
            }
        }
        return out;
    }

    List<WonkoPrompts.SubjectDoc> subject() {
        List<WonkoPrompts.SubjectDoc> out = new ArrayList<>();
        for (String path : state.getSubjectRefs()) {
            String text = doc(path);
            if (text == null) {
                throw new IllegalStateException("subject document '" + path + "' has no readable text content");
            }
            out.add(new WonkoPrompts.SubjectDoc(path, text));
        }
        return out;
    }

    /** Records a non-trivial decision with its reason (plan §4a.8). */
    void log(String subject, String outcome, @Nullable String reason) {
        if (state.getDecisions().size() >= MAX_DECISIONS) {
            return;
        }
        state.getDecisions().add(new WonkoDecision(subject, outcome, reason == null ? "" : reason));
    }
}
