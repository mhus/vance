package de.mhus.vance.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Runtime config for the CLI.
 *
 * <p>Resolution order when a caller does not pass an explicit path:
 * <ol>
 *   <li>The {@code VANCE_CLI_CONFIG} environment variable, if set.</li>
 *   <li>The {@code /application.yaml} bundled on the classpath.</li>
 * </ol>
 *
 * <p>Shape mirrors the {@code vance:} namespace in the YAML. Only the
 * {@code vance:} subtree is bound — everything outside is ignored.
 */
@Data
public class VanceCliConfig {

    private Brain brain = new Brain();
    private Auth auth = new Auth();
    private Client client = new Client();
    private Debug debug = new Debug();
    private @Nullable Bootstrap bootstrap;

    @Data
    public static class Brain {
        private String httpBase = "http://localhost:8080";
        private String wsBase = "ws://localhost:8080";
    }

    @Data
    public static class Auth {
        private String tenant = "";
        private String username = "";
        private String password = "";
    }

    @Data
    public static class Client {
        private String version = "0.1.0";
    }

    /**
     * Debug tooling. Intended for local development only — all endpoints are
     * unauthenticated. Disabled by default; opt in via {@link Rest#isEnabled()}
     * in config or {@code --debug} on the command line.
     */
    @Data
    public static class Debug {
        private Rest rest = new Rest();
    }

    @Data
    public static class Rest {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 8765;
    }

    /**
     * Startup payload for a {@code session-bootstrap} command sent once the
     * WebSocket handshake is done. All fields are optional; their absence
     * means "don't set this field in the request".
     */
    @Data
    public static class Bootstrap {
        private @Nullable String projectId;
        private @Nullable String sessionId;
        private List<Process> processes = new ArrayList<>();
        private @Nullable String initialMessage;
    }

    @Data
    public static class Process {
        private String engine = "";
        private String name = "";
        private @Nullable String title;
        private @Nullable String goal;
    }

    /** Uses the resolution order documented on the class. */
    public static VanceCliConfig load() {
        return load(null);
    }

    /**
     * Loads config from {@code explicit} if non-null; otherwise applies the
     * resolution order on the class javadoc.
     */
    public static VanceCliConfig load(@Nullable Path explicit) {
        Path path = explicit;
        if (path == null) {
            String env = System.getenv("VANCE_CLI_CONFIG");
            if (env != null && !env.isBlank()) {
                path = Path.of(env);
            }
        }
        try {
            if (path != null) {
                return parseFromFile(path);
            }
            return parseFromClasspath();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config", e);
        }
    }

    private static VanceCliConfig parseFromFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Config file not found: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, path.toString());
        }
    }

    private static VanceCliConfig parseFromClasspath() throws IOException {
        try (InputStream in = VanceCliConfig.class.getResourceAsStream("/application.yaml")) {
            if (in == null) {
                throw new IllegalStateException("application.yaml not found on classpath");
            }
            return parse(in, "classpath:/application.yaml");
        }
    }

    private static VanceCliConfig parse(InputStream in, String source) throws IOException {
        YAMLMapper mapper = YAMLMapper.builder().build();
        JsonNode root = mapper.readTree(in);
        JsonNode vance = root.get("vance");
        if (vance == null || vance.isNull()) {
            throw new IllegalStateException(source + ": missing top-level 'vance' node");
        }
        return mapper.treeToValue(vance, VanceCliConfig.class);
    }
}
