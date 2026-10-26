package de.mhus.vance.brain.script;

import de.mhus.vance.shared.workspace.NpmPackageSpec;
import de.mhus.vance.shared.workspace.WorkspaceException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Parses the first JSDoc-block header of a script source into a
 * {@link ScriptHeader}. Implements the rules from
 * {@code specification/script-engine.md} §3.5:
 *
 * <ul>
 *   <li>First {@code /** … *​/}-block above the first executable
 *       statement is the header. Any later block is regular doc.</li>
 *   <li>Each line inside the block is scanned for
 *       {@code @tag value} pairs. Leading {@code *} is stripped
 *       (JSDoc continuation).</li>
 *   <li>Unknown tags warn-log but never throw.</li>
 *   <li>Malformed values for known tags raise
 *       {@link ScriptExecutionException}
 *       {@code (INVALID_HEADER, …)} so the caller fails-fast before
 *       evaluating.</li>
 *   <li>Duplicate same-tag entries: last one wins + warn-log.</li>
 * </ul>
 */
@Slf4j
public final class ScriptHeaderParser {

    private static final Pattern BLOCK_PATTERN = Pattern.compile("\\A\\s*/\\*\\*([\\s\\S]*?)\\*/", Pattern.DOTALL);

    /** Matches one tag line. Group 1 = tag, group 2 = rest of line
     *  trimmed by the caller. Leading {@code *} of JSDoc-continuation
     *  is stripped before this matcher runs. */
    private static final Pattern TAG_PATTERN = Pattern.compile("^\\s*@(\\w+)\\s+(.+?)\\s*$");

    /** Allowed v1 tag names. Unknown names get warn-logged + dropped.
     *  v2 additions (e.g. {@code maxSpawnDepth}, {@code fixture}) get
     *  added here. */
    private static final Set<String> KNOWN_TAGS = Set.of(
            "timeout",
            "statements",
            "maxResultNodes",
            "allowTools",
            "requiresTools",
            "description",
            "version",
            // CommonJS require pathway (vance.script.require.enabled):
            "requires",
            "workspaceRoot",
            "nodeBuiltins",
            // Sandbox limits — narrow vance.script.limits.*, never widen:
            "maxHeapMemory",
            "maxCpuTime");

    private ScriptHeaderParser() {}

    /**
     * @param code        the raw script source
     * @param sourceName  identifier shown in log / error messages
     *                    (typically the on-disk path or skill::script)
     * @return parsed header, or {@link ScriptHeader#empty()} if no
     *         first-block header is present
     * @throws ScriptExecutionException with errorClass INVALID_HEADER
     *         when a known tag carries a malformed value
     */
    public static ScriptHeader parse(@Nullable String code, String sourceName) {
        if (code == null || code.isBlank()) {
            return ScriptHeader.empty();
        }
        Matcher m = BLOCK_PATTERN.matcher(code);
        if (!m.find()) {
            return ScriptHeader.empty();
        }
        String body = m.group(1);

        Duration timeout = null;
        Long statementLimit = null;
        Long maxResultNodes = null;
        Set<String> allowTools = new LinkedHashSet<>();
        Set<String> requiresTools = new LinkedHashSet<>();
        String description = null;
        String version = null;
        Set<String> requires = new LinkedHashSet<>();
        String workspaceRoot = null;
        Set<String> nodeBuiltins = new LinkedHashSet<>();
        Long maxHeapMemory = null;
        Duration maxCpuTime = null;
        boolean anyTag = false;
        // Track which tags we've seen so we can warn on duplicates.
        Set<String> seen = new LinkedHashSet<>();

        for (String rawLine : body.split("\\r?\\n")) {
            String line = stripJsdocStar(rawLine);
            Matcher tm = TAG_PATTERN.matcher(line);
            if (!tm.matches()) continue;
            String tag = tm.group(1);
            String value = tm.group(2).trim();
            if (!KNOWN_TAGS.contains(tag)) {
                log.warn(
                        "ScriptHeaderParser [{}]: unknown tag '@{}' " + "with value '{}' — ignored",
                        sourceName,
                        tag,
                        abbreviate(value));
                continue;
            }
            if (!seen.add(tag)) {
                log.warn(
                        "ScriptHeaderParser [{}]: duplicate tag '@{}' " + "— previous value overwritten with '{}'",
                        sourceName,
                        tag,
                        abbreviate(value));
            }
            switch (tag) {
                case "timeout" -> timeout = parseDuration(value, sourceName, tag);
                case "statements" -> statementLimit = parseCount(value, sourceName, tag);
                case "maxResultNodes" -> maxResultNodes = parseCount(value, sourceName, tag);
                case "allowTools" -> {
                    allowTools.clear();
                    allowTools.addAll(parseList(value));
                }
                case "requiresTools" -> {
                    requiresTools.clear();
                    requiresTools.addAll(parseList(value));
                }
                case "description" -> description = value;
                case "version" -> version = value;
                case "requires" -> {
                    requires.clear();
                    requires.addAll(parsePackageSpecs(value, sourceName, tag));
                }
                case "workspaceRoot" -> workspaceRoot = value;
                case "nodeBuiltins" -> {
                    nodeBuiltins.clear();
                    nodeBuiltins.addAll(parseList(value));
                }
                case "maxHeapMemory" -> maxHeapMemory = parseSize(value, sourceName, tag);
                case "maxCpuTime" -> maxCpuTime = parseDuration(value, sourceName, tag);
                default -> {
                    /* unreachable due to KNOWN_TAGS check */
                }
            }
            anyTag = true;
        }
        if (!anyTag) {
            return ScriptHeader.empty();
        }
        return new ScriptHeader(
                timeout,
                statementLimit,
                maxResultNodes,
                allowTools,
                requiresTools,
                description,
                version,
                requires,
                workspaceRoot,
                nodeBuiltins,
                maxHeapMemory,
                maxCpuTime);
    }

    // ──────────────────── value parsers ────────────────────

    /**
     * Bare integer = seconds. Suffix {@code s}/{@code m}/{@code h} =
     * scaled. Underscores are stripped as thousands-separators.
     */
    private static Duration parseDuration(String raw, String sourceName, String tag) {
        String normalized = raw.replace("_", "").trim().toLowerCase();
        try {
            if (normalized.endsWith("s")) {
                long v = Long.parseLong(normalized.substring(0, normalized.length() - 1));
                requirePositive(v, raw, sourceName, tag);
                return Duration.ofSeconds(v);
            }
            if (normalized.endsWith("m")) {
                long v = Long.parseLong(normalized.substring(0, normalized.length() - 1));
                requirePositive(v, raw, sourceName, tag);
                return Duration.ofMinutes(v);
            }
            if (normalized.endsWith("h")) {
                long v = Long.parseLong(normalized.substring(0, normalized.length() - 1));
                requirePositive(v, raw, sourceName, tag);
                return Duration.ofHours(v);
            }
            long v = Long.parseLong(normalized);
            requirePositive(v, raw, sourceName, tag);
            return Duration.ofSeconds(v);
        } catch (NumberFormatException nfe) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.INVALID_HEADER,
                    "[" + sourceName + "] @" + tag + ": cannot parse "
                            + "duration value '" + raw + "' "
                            + "(expected formats: 30s | 10m | 1h | 600)",
                    nfe);
        }
    }

    /**
     * Statement-count: bare integer, plus {@code k}/{@code M} suffixes
     * for thousand/million. Underscores stripped.
     */
    private static long parseCount(String raw, String sourceName, String tag) {
        String normalized = raw.replace("_", "").trim();
        try {
            long multiplier = 1L;
            String digits = normalized;
            if (normalized.endsWith("k") || normalized.endsWith("K")) {
                multiplier = 1_000L;
                digits = normalized.substring(0, normalized.length() - 1);
            } else if (normalized.endsWith("M")) {
                multiplier = 1_000_000L;
                digits = normalized.substring(0, normalized.length() - 1);
            }
            long base = Long.parseLong(digits);
            long product = base * multiplier;
            requirePositive(product, raw, sourceName, tag);
            return product;
        } catch (NumberFormatException nfe) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.INVALID_HEADER,
                    "[" + sourceName + "] @" + tag + ": cannot parse "
                            + "count value '" + raw + "' "
                            + "(expected formats: 100000 | 100k | 5M | 1_000_000)",
                    nfe);
        }
    }

    /**
     * Byte size: bare integer = bytes, suffix {@code KB}/{@code MB}/{@code GB}
     * (case-insensitive, binary multiples). Underscores stripped.
     */
    private static long parseSize(String raw, String sourceName, String tag) {
        String normalized = raw.replace("_", "").trim().toUpperCase();
        try {
            long multiplier = 1L;
            String digits = normalized;
            if (normalized.endsWith("KB")) {
                multiplier = 1024L;
                digits = normalized.substring(0, normalized.length() - 2);
            } else if (normalized.endsWith("MB")) {
                multiplier = 1024L * 1024L;
                digits = normalized.substring(0, normalized.length() - 2);
            } else if (normalized.endsWith("GB")) {
                multiplier = 1024L * 1024L * 1024L;
                digits = normalized.substring(0, normalized.length() - 2);
            }
            long product = Math.multiplyExact(Long.parseLong(digits.trim()), multiplier);
            requirePositive(product, raw, sourceName, tag);
            return product;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.INVALID_HEADER,
                    "[" + sourceName + "] @" + tag + ": cannot parse "
                            + "size value '" + raw + "' "
                            + "(expected formats: 64MB | 512KB | 1GB | 1048576)",
                    e);
        }
    }

    /**
     * Package list for {@code @requires}: registry specs ({@code name} or
     * {@code name@range}, no spaces inside a range). Anything npm would read as a
     * path, URL or option is rejected here, before the script runs.
     */
    private static Set<String> parsePackageSpecs(String raw, String sourceName, String tag) {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : parseList(raw)) {
            try {
                out.add(NpmPackageSpec.parse(entry).toArgument());
            } catch (WorkspaceException e) {
                throw new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.INVALID_HEADER,
                        "[" + sourceName + "] @" + tag + ": " + e.getMessage(),
                        e);
            }
        }
        return out;
    }

    /** Splits on comma OR whitespace; trims; drops empties; dedupes. */
    private static Set<String> parseList(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[\\s,]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    // ──────────────────── helpers ────────────────────

    private static String stripJsdocStar(String line) {
        // Leading whitespace then optional '* ' (JSDoc continuation).
        return line.replaceFirst("^\\s*\\*\\s?", "");
    }

    private static void requirePositive(long value, String raw, String sourceName, String tag) {
        if (value <= 0) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.INVALID_HEADER,
                    "[" + sourceName + "] @" + tag + ": value '" + raw + "' must be > 0");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "(null)";
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }
}
