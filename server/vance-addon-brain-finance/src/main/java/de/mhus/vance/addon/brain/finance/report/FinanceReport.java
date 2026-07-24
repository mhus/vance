package de.mhus.vance.addon.brain.finance.report;

import org.jspecify.annotations.Nullable;

/**
 * The output of a {@link FinanceReportProcessor}: a fully-serialised document
 * body for an existing kind, ready to persist or return inline.
 *
 * @param outputKind    the target kind ({@code sheet}, {@code chart}, …).
 * @param mimeType      the body's wire format ({@code application/yaml} / json).
 * @param body          the serialised body, produced via the target kind's
 *                      codec (byte-identical round-trip).
 * @param suggestedName a suggested file name for the persisted report, or
 *                      {@code null} to let the caller choose.
 */
public record FinanceReport(
        String outputKind,
        String mimeType,
        String body,
        @Nullable String suggestedName) {}
