package de.mhus.vance.addon.brain.finance.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A node in the finance tree. Carries display metadata plus its own additive
 * {@link FinanceValue} records and recursive {@code children}.
 *
 * <p>{@code sign} ({@code +1}/{@code -1}) applies to the node's <em>total</em>
 * contribution: {@code total = sign × (Σ own values + Σ children totals)}. A
 * cost sub-tree is a node with {@code sign = -1} over positively-entered
 * children.
 *
 * <p>{@code name} is the business key within the tree scope; {@code title} is
 * the display label; {@code notesRef} points at a separate notes document.
 */
public record FinanceNode(
        String name,
        @Nullable String title,
        @Nullable String icon,
        @Nullable String color,
        int sign,
        @Nullable String description,
        @Nullable String notesRef,
        List<FinanceValue> values,
        List<FinanceNode> children) {}
