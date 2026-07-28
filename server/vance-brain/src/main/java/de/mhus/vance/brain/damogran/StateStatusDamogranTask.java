package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code state-status} task: inspects the Damogran {@link
 * DamogranStateService state store} for the current document and renders the
 * result into the notebook output region (like a cell that prints kernel state).
 *
 * <p>Params:
 * <ul>
 *   <li>{@code for} — a single state type (exec/python/js/r); absent = all types;</li>
 *   <li>{@code mode} — {@code info} (default: list files with mtime + size),
 *       or {@code header}/{@code footer}/{@code cache} (the file content;
 *       requires {@code for}).</li>
 * </ul>
 * WORK only (reads server-side files); a run without a state store reports so.
 */
@Service
class StateStatusDamogranTask implements DamogranTask {

    private final DamogranStateService stateService;

    StateStatusDamogranTask(DamogranStateService stateService) {
        this.stateService = stateService;
    }

    @Override
    public String type() {
        return "state-status";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        String forType = DamogranTaskSupport.string(spec, "for");
        String mode = DamogranTaskSupport.string(spec, "mode");
        if (mode == null) {
            mode = "info";
        }

        if (!"info".equals(mode)) {
            if (forType == null) {
                return DamogranTaskResult.failure(
                        "state-status mode '" + mode + "' requires 'for: <type>'");
            }
            return contentOf(ctx, forType, mode);
        }

        // info mode
        List<String> types = forType != null ? List.of(forType) : stateService.listTypes(ctx);
        if (types.isEmpty()) {
            return DamogranTaskResult.success(List.of(), "no state store for this document");
        }
        StringBuilder out = new StringBuilder();
        for (String t : types) {
            DamogranStateService.StateDir sd = stateService.resolve(ctx, t);
            out.append(t).append(":\n");
            if (sd == null) {
                out.append("  (no state)\n");
                continue;
            }
            line(out, "header", sd.headerPath());
            line(out, "footer", sd.footerPath());
            line(out, sd.cacheFileName(), sd.cachePath());
        }
        return DamogranTaskResult.success(List.of(), out.toString().stripTrailing());
    }

    private DamogranTaskResult contentOf(DamogranContext ctx, String forType, String mode) {
        DamogranStateService.StateDir sd = stateService.resolve(ctx, forType);
        if (sd == null) {
            return DamogranTaskResult.success(List.of(), "no state for type '" + forType + "'");
        }
        String content = switch (mode) {
            case "header" -> sd.readHeader();
            case "footer" -> sd.readFooter();
            case "cache" -> sd.readCache();
            default -> null;
        };
        if (content == null) {
            return DamogranTaskResult.failure(
                    "state-status mode must be one of info/header/footer/cache (was: " + mode + ")");
        }
        return DamogranTaskResult.success(List.of(), content);
    }

    /** Append a {@code  name: <size>B  <mtime>} line, or mark the file absent. */
    private static void line(StringBuilder out, String name, Path file) {
        out.append("  ").append(name).append(": ");
        if (!Files.isRegularFile(file)) {
            out.append("(absent)\n");
            return;
        }
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            out.append(a.size()).append("B  ").append(a.lastModifiedTime().toInstant()).append('\n');
        } catch (IOException e) {
            out.append("(stat failed: ").append(e.getMessage()).append(")\n");
        }
    }
}
