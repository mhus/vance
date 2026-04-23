package de.mhus.vance.cli;

import java.io.IOException;
import java.io.InputStream;
import lombok.Data;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Runtime config for the CLI, loaded from the {@code application.yaml} on the
 * classpath (fallback) or from {@code $VANCE_CLI_CONFIG} if set.
 *
 * <p>Shape mirrors the {@code vance:} namespace in the YAML. Only the
 * {@code vance:} subtree is bound — everything outside is ignored.
 */
@Data
public class VanceCliConfig {

    private Brain brain = new Brain();
    private Auth auth = new Auth();
    private Client client = new Client();

    @Data
    public static class Brain {
        private String httpBase = "http://localhost:8080";
        private String wsBase = "ws://localhost:8080";
        private String wsPath = "/brain/ws";
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

    public static VanceCliConfig load() {
        try (InputStream in = VanceCliConfig.class.getResourceAsStream("/application.yaml")) {
            if (in == null) {
                throw new IllegalStateException("application.yaml not found on classpath");
            }
            YAMLMapper mapper = YAMLMapper.builder().build();
            JsonNode root = mapper.readTree(in);
            JsonNode vance = root.get("vance");
            if (vance == null || vance.isNull()) {
                throw new IllegalStateException("application.yaml: missing top-level 'vance' node");
            }
            return mapper.treeToValue(vance, VanceCliConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.yaml", e);
        }
    }
}
