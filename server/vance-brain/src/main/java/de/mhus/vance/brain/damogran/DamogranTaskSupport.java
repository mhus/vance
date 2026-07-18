package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.OutputSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared helpers for built-in {@link DamogranTask} beans: reading typed params
 * off a {@link TaskSpec} and resolving declared outputs into
 * {@link OutputArtifact}s (kind/mime from {@link DamogranMime} unless the
 * manifest overrode the kind).
 */
final class DamogranTaskSupport {

    private DamogranTaskSupport() {}

    static @Nullable String string(TaskSpec spec, String key) {
        Object raw = spec.params().get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    static String requireString(TaskSpec spec, String key) {
        String value = string(spec, key);
        if (value == null) {
            throw new DamogranException(
                    "task '" + spec.type() + "' requires parameter '" + key + "'");
        }
        return value;
    }

    static int intOr(TaskSpec spec, String key, int fallback) {
        Object raw = spec.params().get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new DamogranException(
                        "task '" + spec.type() + "' parameter '" + key + "' must be an integer");
            }
        }
        return fallback;
    }

    /**
     * Interprets an {@code ExecManager} rendered result map (keys
     * {@code status}/{@code exitCode}/{@code stdout}/{@code stderr}) into a
     * task result. Success = {@code COMPLETED} with exit code 0.
     */
    static DamogranTaskResult fromExec(
            Map<String, Object> rendered, String command, List<OutputArtifact> outputs) {
        String status = String.valueOf(rendered.get("status"));
        Object exit = rendered.get("exitCode");
        String stdout = text(rendered.get("stdout"));
        String stderr = text(rendered.get("stderr"));
        boolean ok = "COMPLETED".equals(status) && exit instanceof Number n && n.intValue() == 0;
        String log = stdout.isBlank() ? stderr
                : (stderr.isBlank() ? stdout : stdout + "\n" + stderr);
        if (ok) {
            return DamogranTaskResult.success(outputs, log);
        }
        String detail = stderr.isBlank() ? "" : ": " + cap(stderr);
        return DamogranTaskResult.failure(
                "'" + command + "' status=" + status + " exit=" + exit + detail, log);
    }

    private static String text(@Nullable Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private static String cap(String s) {
        String trimmed = s.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    /** Resolves the task's declared outputs into renderable artifacts. */
    static List<OutputArtifact> resolveOutputs(TaskSpec spec) {
        List<OutputArtifact> result = new ArrayList<>();
        for (OutputSpec out : spec.declaredOutputs()) {
            String kind = out.kind() != null ? out.kind() : DamogranMime.kindForPath(out.path());
            result.add(new OutputArtifact(
                    out.path(), kind, DamogranMime.mimeForPath(out.path()), out.title()));
        }
        return List.copyOf(result);
    }
}
