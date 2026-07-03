package de.mhus.vance.addon.brain.workbook.validate;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Reusable reference checks shared by the block validators, so each validator
 * stays a thin composition of these. Every method appends zero or more
 * {@link Finding}s to {@code out}. Operates on the already-typed field values
 * of a {@link de.mhus.vance.addon.brain.workpage.Block} (booleans etc. are
 * validated by the parser/model, so only references need checking here).
 */
final class Checks {

    private Checks() {}

    /**
     * A {@code vance:} document reference ({@code rawRef}) under the logical
     * {@code label}. When {@code required} and blank → error. When present: it
     * must resolve, the target must exist, and — if {@code expectKind} is given
     * — a mismatching target kind warns. Returns the resolved {@link VanceRef}.
     */
    static @Nullable VanceRef docRef(
            List<Finding> out, ValidationContext ctx, String label,
            @Nullable String rawRef, boolean required, @Nullable String expectKind) {
        if (rawRef == null || rawRef.isBlank()) {
            if (required) {
                out.add(Finding.error(ctx.location(), "missing-" + label,
                        "`" + label + "` is required but missing."));
            }
            return null;
        }
        VanceRef ref = ctx.resolve(rawRef);
        if (ref == null) {
            out.add(Finding.error(ctx.location(), "bad-" + label,
                    "`" + label + "` is not a usable reference: '" + rawRef + "'."));
            return null;
        }
        if (!ctx.docs().exists(ref.path())) {
            out.add(Finding.error(ctx.location(), "unresolved-" + label,
                    "`" + label + "` points to a document that does not exist: '"
                            + ref.path() + "'."));
            return ref;
        }
        if (expectKind != null) {
            String actual = ctx.docs().kindOf(ref.path());
            if (actual != null && !expectKind.equals(actual)) {
                out.add(Finding.warning(ctx.location(), "kind-mismatch-" + label,
                        "`" + label + "` target '" + ref.path() + "' has kind '"
                                + actual + "', expected '" + expectKind + "'."));
            }
        }
        return ref;
    }

    /**
     * A {@code .js} script reference under {@code label}. When {@code required}
     * and blank → error. When present: must resolve, end in {@code .js}, and
     * the target document must exist.
     */
    static void scriptRef(
            List<Finding> out, ValidationContext ctx, String label,
            @Nullable String rawRef, boolean required) {
        if (rawRef == null || rawRef.isBlank()) {
            if (required) {
                out.add(Finding.error(ctx.location(), "missing-" + label,
                        "`" + label + "` is required but missing."));
            }
            return;
        }
        VanceRef ref = ctx.resolve(rawRef);
        if (ref == null) {
            out.add(Finding.error(ctx.location(), "bad-" + label,
                    "`" + label + "` is not a usable reference: '" + rawRef + "'."));
            return;
        }
        if (!ref.path().toLowerCase(Locale.ROOT).endsWith(".js")) {
            out.add(Finding.error(ctx.location(), "not-js-" + label,
                    "`" + label + "` must be a .js document (got '" + ref.path()
                            + "') — only in-JVM JavaScript is supported."));
            return;
        }
        if (!ctx.docs().exists(ref.path())) {
            out.add(Finding.error(ctx.location(), "unresolved-" + label,
                    "`" + label + "` script does not exist: '" + ref.path()
                            + "'. Create it as a project document with doc_write,"
                            + " not work_file_write."));
        }
    }
}
