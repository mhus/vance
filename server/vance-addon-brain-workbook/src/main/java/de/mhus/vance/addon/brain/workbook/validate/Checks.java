package de.mhus.vance.addon.brain.workbook.validate;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Reusable reference/attribute checks shared by the block validators, so
 * each validator stays a thin composition of these. Every method appends
 * zero or more {@link Finding}s to {@code out}.
 */
final class Checks {

    private Checks() {}

    /**
     * A {@code vance:} document reference in {@code key}. When {@code required}
     * and absent → error. When present: must resolve, the target must exist,
     * and — if {@code expectKind} is given — a mismatching target kind warns.
     * Returns the resolved {@link VanceRef} (or {@code null}).
     */
    static @Nullable VanceRef docRef(
            List<Finding> out, FenceBlock b, ValidationContext ctx,
            String key, boolean required, @Nullable String expectKind) {
        String raw = b.str(key);
        if (raw == null) {
            if (required) {
                out.add(Finding.error(b.location(), "missing-" + key,
                        "`" + key + "` is required but missing."));
            }
            return null;
        }
        VanceRef ref = ctx.resolve(raw);
        if (ref == null) {
            out.add(Finding.error(b.location(), "bad-" + key,
                    "`" + key + "` is not a usable reference: '" + raw + "'."));
            return null;
        }
        if (!ctx.docs().exists(ref.path())) {
            out.add(Finding.error(b.location(), "unresolved-" + key,
                    "`" + key + "` points to a document that does not exist: '"
                            + ref.path() + "'."));
            return ref;
        }
        if (expectKind != null) {
            String actual = ctx.docs().kindOf(ref.path());
            if (actual != null && !expectKind.equals(actual)) {
                out.add(Finding.warning(b.location(), "kind-mismatch-" + key,
                        "`" + key + "` target '" + ref.path() + "' has kind '"
                                + actual + "', expected '" + expectKind + "'."));
            }
        }
        return ref;
    }

    /**
     * A {@code .js} script reference in {@code key}. When {@code required} and
     * absent → error. When present: must resolve, end in {@code .js}, and the
     * target document must exist.
     */
    static void scriptRef(
            List<Finding> out, FenceBlock b, ValidationContext ctx,
            String key, boolean required) {
        String raw = b.str(key);
        if (raw == null) {
            if (required) {
                out.add(Finding.error(b.location(), "missing-" + key,
                        "`" + key + "` is required but missing."));
            }
            return;
        }
        VanceRef ref = ctx.resolve(raw);
        if (ref == null) {
            out.add(Finding.error(b.location(), "bad-" + key,
                    "`" + key + "` is not a usable reference: '" + raw + "'."));
            return;
        }
        if (!ref.path().toLowerCase(Locale.ROOT).endsWith(".js")) {
            out.add(Finding.error(b.location(), "not-js-" + key,
                    "`" + key + "` must be a .js document (got '" + ref.path()
                            + "') — only in-JVM JavaScript is supported."));
            return;
        }
        if (!ctx.docs().exists(ref.path())) {
            out.add(Finding.error(b.location(), "unresolved-" + key,
                    "`" + key + "` script does not exist: '" + ref.path()
                            + "'. Create it as a project document with doc_write,"
                            + " not work_file_write."));
        }
    }

    /** A fence attribute that must be boolean if present. */
    static void boolAttr(List<Finding> out, FenceBlock b, String key) {
        if (b.isNonBoolean(key)) {
            out.add(Finding.warning(b.location(), "not-boolean-" + key,
                    "`" + key + "` should be true/false."));
        }
    }
}
