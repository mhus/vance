package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One item in a {@code kind: checklist} document. Carries mandatory
 * {@code text}, a {@link ChecklistStatus} (default {@link
 * ChecklistStatus#OPEN}) and an optional {@link ChecklistPriority}.
 *
 * <p>{@code extra} carries any per-item fields the codec doesn't know
 * — round-trips losslessly through JSON and YAML. Markdown also
 * round-trips two specific keys via convention: {@code _statusChar}
 * (preserves custom single-char status markers like {@code [Z]}) and
 * any tag injected as a trailing {@code #prio:*} marker.
 *
 * <p>Spec: {@code specification/doc-kind-checklist.md} §2.
 */
public record ChecklistItem(
        String text,
        ChecklistStatus status,
        @Nullable ChecklistPriority priority,
        Map<String, Object> extra) {

    public ChecklistItem {
        Objects.requireNonNull(text, "text");
        if (status == null) status = ChecklistStatus.OPEN;
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Convenience: an item with given text, default status, no extras. */
    public static ChecklistItem of(String text) {
        return new ChecklistItem(text, ChecklistStatus.OPEN, null, new LinkedHashMap<>());
    }

    /** Convenience: an item with given text and status. */
    public static ChecklistItem of(String text, ChecklistStatus status) {
        return new ChecklistItem(text, status, null, new LinkedHashMap<>());
    }
}
