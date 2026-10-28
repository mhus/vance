package de.mhus.vance.runner;

import de.mhus.vance.runner.protocol.RunnerEnvelope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The two operations every runner has: {@code ping} and {@code info}. */
final class BasicOperations {

    private BasicOperations() {}

    /** Proves the whole chain — envelope, key, token — works; carries no data. */
    static RunnerOperation ping(RunnerConfig config) {
        return new RunnerOperation() {
            @Override
            public String name() {
                return "ping";
            }

            @Override
            public OperationResult execute(Call call) {
                return OperationResult.of(Map.of("pong", true, "runnerId", config.runnerId()));
            }
        };
    }

    /** What this runner is and what it has to offer. */
    static RunnerOperation info(RunnerConfig config, List<String> capabilities) {
        return new RunnerOperation() {
            @Override
            public String name() {
                return "info";
            }

            @Override
            public OperationResult execute(Call call) throws OperationException {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("runnerId", config.runnerId());
                info.put("tenant", config.tenantId());
                info.put("version", version());
                info.put("protocol", RunnerEnvelope.VERSION);
                info.put("java", Runtime.version().toString());
                info.put("capabilities", capabilities);
                info.put("data", volume(config.dataDir()));
                return OperationResult.of(info);
            }
        };
    }

    static String version() {
        String version = BasicOperations.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static Map<String, Object> volume(Path dir) throws RunnerOperation.OperationException {
        try {
            Files.createDirectories(dir);
            var store = Files.getFileStore(dir);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            return Map.of(
                    "freeBytes", free,
                    "totalBytes", total,
                    "usedPercent", total <= 0 ? 0 : (int) (100 - (free * 100 / total)));
        } catch (IOException e) {
            throw new RunnerOperation.OperationException(
                    "volume-unavailable", "Cannot read the data volume: " + e.getMessage(), e);
        }
    }
}
