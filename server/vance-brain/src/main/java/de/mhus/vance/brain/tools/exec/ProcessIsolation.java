package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.shared.process.ProcessSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.util.unit.DataSize;

/**
 * Pure helper for the starters behind the {@code ProcessLauncher}: wraps a
 * {@link ProcessSpec}'s argv in {@code vance.exec.isolation.process-wrapper} and
 * the {@code prlimit} caps of {@code vance.exec.process-limits}
 * ({@code planning/multi-tenant-isolation.md} phase 2c). The result is an argv list
 * for {@code ProcessBuilder} — never re-interpreted by a shell.
 *
 * <p>Order: {@code prlimit …} outside, then the wrapper, then the starter's argv.
 * The caps are inherited through the wrapper (P15).
 */
final class ProcessIsolation {

    private ProcessIsolation() {}

    /** Wrapping applies only with {@code mode: custom} and a process wrapper. */
    static boolean enabled(ExecProperties.@Nullable Isolation iso) {
        return iso != null
                && "custom".equals(iso.getMode())
                && iso.getProcessWrapper() != null
                && !iso.getProcessWrapper().isBlank();
    }

    /**
     * The argv to start for {@code spec}.
     *
     * @param home    the child's {@code HOME} — fills {@code {home}}
     * @param workdir the child's working directory — fills {@code {workdir}}
     */
    static List<String> argv(
            ProcessSpec spec,
            Path home,
            Path workdir,
            ExecProperties.Isolation iso,
            ExecProperties.ProcessLimits limits) {
        List<String> argv = new ArrayList<>(prlimit(limits));
        if (enabled(iso)) {
            argv.addAll(wrapper(spec, home, workdir, iso));
        }
        argv.addAll(spec.command());
        return argv;
    }

    private static List<String> wrapper(ProcessSpec spec, Path home, Path workdir, ExecProperties.Isolation iso) {
        String template = iso.getProcessWrapper();
        List<String> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        for (String token : tokens(template)) {
            switch (token) {
                case "{binds}" -> {
                    for (Path path : spec.writablePaths()) {
                        out.addAll(perPath(iso.getBindArgs(), path));
                    }
                }
                case "{ro-binds}" -> {
                    for (Path path : spec.readablePaths()) {
                        out.addAll(perPath(iso.getReadOnlyBindArgs(), path));
                    }
                }
                case "{net}" -> {
                    if (!spec.network()) {
                        out.addAll(tokens(iso.getNoNetworkArgs()));
                    }
                }
                default -> out.add(token.replace("{home}", home.toString()).replace("{workdir}", workdir.toString()));
            }
        }
        return out;
    }

    private static List<String> perPath(String template, Path path) {
        String absolute = path.toAbsolutePath().normalize().toString();
        List<String> out = new ArrayList<>();
        for (String token : tokens(template)) {
            out.add(token.replace("{path}", absolute));
        }
        return out;
    }

    private static List<String> prlimit(ExecProperties.ProcessLimits limits) {
        List<String> caps = new ArrayList<>();
        DataSize addressSpace = limits.getAddressSpace();
        if (addressSpace != null) {
            caps.add("--as=" + addressSpace.toBytes());
        }
        Long cpuSeconds = limits.getCpuSeconds();
        if (cpuSeconds != null) {
            caps.add("--cpu=" + cpuSeconds);
        }
        DataSize fileSize = limits.getFileSize();
        if (fileSize != null) {
            caps.add("--fsize=" + fileSize.toBytes());
        }
        if (caps.isEmpty()) {
            return caps;
        }
        caps.addFirst(limits.getPrlimitPath());
        return caps;
    }

    private static List<String> tokens(String template) {
        String trimmed = template.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("\\s+"));
    }
}
