package de.mhus.vance.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wraps a {@link ProcessSpec}'s argv in the configured process wrapper and the
 * {@code prlimit} caps ({@code planning/multi-tenant-isolation.md} phase 2c). The
 * result is an argv list for {@code ProcessBuilder} — never re-interpreted by a
 * shell. The brain feeds it from {@code vance.exec.isolation.*} and
 * {@code vance.exec.process-limits.*}; the runner from its own configuration.
 *
 * <p>Order: {@code prlimit …} outside, then the wrapper, then the starter's argv.
 * The caps are inherited through the wrapper (P15).
 */
public final class ProcessIsolation {

    /**
     * The process wrapper template and how one declared path or "no network" is
     * spelled in it.
     *
     * @param processWrapper   whitespace-separated argv template; {@code {home}} and
     *                         {@code {workdir}} are substituted inside a token, the
     *                         tokens {@code {binds}}, {@code {ro-binds}} and
     *                         {@code {net}} expand; blank or {@code null} = unwrapped
     * @param bindArgs         tokens for one writable path ({@code {path}})
     * @param readOnlyBindArgs tokens for one readable path ({@code {path}})
     * @param noNetworkArgs    tokens that cut a starter off the network
     */
    public record Wrapper(
            @Nullable String processWrapper, String bindArgs, String readOnlyBindArgs, String noNetworkArgs) {

        /** bubblewrap's spelling for the per-path and network tokens. */
        public static Wrapper bubblewrap(@Nullable String processWrapper) {
            return new Wrapper(processWrapper, "--bind {path} {path}", "--ro-bind {path} {path}", "--unshare-net");
        }

        public boolean enabled() {
            return processWrapper != null && !processWrapper.isBlank();
        }
    }

    /**
     * {@code prlimit} caps; {@code null} leaves a resource uncapped.
     *
     * @param prlimitPath       the {@code prlimit} binary
     * @param addressSpaceBytes {@code --as}
     * @param cpuSeconds        {@code --cpu}
     * @param fileSizeBytes     {@code --fsize}
     */
    public record Limits(
            String prlimitPath,
            @Nullable Long addressSpaceBytes,
            @Nullable Long cpuSeconds,
            @Nullable Long fileSizeBytes) {

        public static final Limits NONE = new Limits("prlimit", null, null, null);
    }

    private ProcessIsolation() {}

    /**
     * The argv to start for {@code spec}.
     *
     * @param home    the child's {@code HOME} — fills {@code {home}}
     * @param workdir the child's working directory — fills {@code {workdir}}
     */
    public static List<String> argv(ProcessSpec spec, Path home, Path workdir, Wrapper wrapper, Limits limits) {
        List<String> argv = new ArrayList<>(prlimit(limits));
        if (wrapper.enabled()) {
            argv.addAll(wrapper(spec, home, workdir, wrapper));
        }
        argv.addAll(spec.command());
        return argv;
    }

    private static List<String> wrapper(ProcessSpec spec, Path home, Path workdir, Wrapper iso) {
        String template = iso.processWrapper();
        List<String> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        for (String token : tokens(template)) {
            switch (token) {
                case "{binds}" -> {
                    for (Path path : spec.writablePaths()) {
                        out.addAll(perPath(iso.bindArgs(), path));
                    }
                }
                case "{ro-binds}" -> {
                    for (Path path : spec.readablePaths()) {
                        out.addAll(perPath(iso.readOnlyBindArgs(), path));
                    }
                }
                case "{net}" -> {
                    if (!spec.network()) {
                        out.addAll(tokens(iso.noNetworkArgs()));
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

    private static List<String> prlimit(Limits limits) {
        List<String> caps = new ArrayList<>();
        Long addressSpace = limits.addressSpaceBytes();
        if (addressSpace != null) {
            caps.add("--as=" + addressSpace);
        }
        Long cpuSeconds = limits.cpuSeconds();
        if (cpuSeconds != null) {
            caps.add("--cpu=" + cpuSeconds);
        }
        Long fileSize = limits.fileSizeBytes();
        if (fileSize != null) {
            caps.add("--fsize=" + fileSize);
        }
        if (caps.isEmpty()) {
            return caps;
        }
        caps.addFirst(limits.prlimitPath());
        return caps;
    }

    private static List<String> tokens(String template) {
        String trimmed = template.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("\\s+"));
    }
}
