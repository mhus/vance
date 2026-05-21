package de.mhus.vance.foot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Tracks the latest active {@code ASK_USER} picker the user could
 * still answer by number. The brain attaches structured options to
 * the chat-message via the {@code askUserOptions} key in
 * {@code ChatMessageAppendedData.meta} (server-side source of truth:
 * {@code de.mhus.vance.shared.chat.ChatMessageDocument.META_ASK_USER_OPTIONS}).
 * The foot client converts them into a numeric shortcut so the user
 * can type {@code 1} / {@code 2} / {@code 3} instead of the full
 * label.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link ChatMessageAppendedHandler} (assistant turn with
 *       options) → {@link #present(List)}.</li>
 *   <li>{@link ChatMessageAppendedHandler} (user echo) or
 *       {@link ChatInputService} (slash command / blank input) →
 *       {@link #clear()}.</li>
 *   <li>Newer assistant turn with options → {@link #present(List)}
 *       replaces the previous set; only one picker is active at a
 *       time.</li>
 * </ul>
 *
 * <p>Thread-safety: the field is an {@link AtomicReference} so the
 * WebSocket-handler thread (mutator) and the JLine REPL thread
 * (reader) can hand-off without locking.
 */
@Component
public class PendingAskUserPicker {

    /**
     * Wire-format key inside {@code ChatMessageAppendedData.meta}.
     * Kept in sync with
     * {@code ChatMessageDocument.META_ASK_USER_OPTIONS} on the
     * server side. Inlined here because the foot module does not
     * depend on {@code vance-shared}.
     */
    private static final String META_ASK_USER_OPTIONS = "askUserOptions";

    private final AtomicReference<List<Option>> active =
            new AtomicReference<>(List.of());

    /**
     * Replaces the active picker. Empty / null list clears it (same
     * as {@link #clear()}).
     */
    public void present(@Nullable List<Option> options) {
        if (options == null || options.isEmpty()) {
            active.set(List.of());
            return;
        }
        active.set(List.copyOf(options));
    }

    public void clear() {
        active.set(List.of());
    }

    public List<Option> snapshot() {
        return active.get();
    }

    public boolean hasActive() {
        return !active.get().isEmpty();
    }

    /**
     * Resolves a one-based numeric input ({@code "1"}, {@code "2"} …)
     * to the matching option's label. Returns {@code null} for
     * non-numeric input, out-of-range numbers, or when no picker is
     * active — caller falls through to the regular chat-send path
     * and the input goes verbatim.
     */
    public @Nullable String resolveNumericPick(String trimmedInput) {
        List<Option> opts = active.get();
        if (opts.isEmpty()) return null;
        if (trimmedInput == null || trimmedInput.isEmpty()) return null;
        int n;
        try {
            n = Integer.parseInt(trimmedInput);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (n < 1 || n > opts.size()) return null;
        return opts.get(n - 1).label();
    }

    /**
     * Parses the structured meta value attached by the brain into a
     * list of {@link Option} entries. Tolerates anything that isn't
     * the expected shape — defensive against future schema drift.
     */
    @SuppressWarnings("unchecked")
    public static List<Option> parseOptions(@Nullable Map<String, Object> meta) {
        if (meta == null) return List.of();
        Object raw = meta.get(META_ASK_USER_OPTIONS);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<Option> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object label = m.get("label");
            if (!(label instanceof String l) || l.isBlank()) continue;
            Object desc = m.get("description");
            String description = desc instanceof String d && !d.isBlank() ? d : null;
            out.add(new Option(l.trim(), description));
        }
        return out;
    }

    public record Option(String label, @Nullable String description) {}
}
