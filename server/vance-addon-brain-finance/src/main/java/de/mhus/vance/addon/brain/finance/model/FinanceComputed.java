package de.mhus.vance.addon.brain.finance.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The computed overlay for a finance tree — a timestamp plus one
 * {@link NodeSnapshot} per node in pre-order. Serialized under the
 * {@code $computed} key by {@link de.mhus.vance.addon.brain.finance.FinanceTreeCodec};
 * produced by {@link de.mhus.vance.addon.brain.finance.FinanceCalculator} +
 * the service (which stamps {@code computedAt}).
 */
public record FinanceComputed(
        @Nullable String computedAt,
        List<NodeSnapshot> nodes) {}
