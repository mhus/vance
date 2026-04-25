package de.mhus.vance.foot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root configuration for the Foot CLI. Bound to the {@code vance} prefix in
 * {@code application.yaml}. Mutable POJO so Spring's relaxed binding can fill
 * fields directly.
 */
@Data
@ConfigurationProperties(prefix = "vance")
public class FootConfig {

    private Brain brain = new Brain();
    private Auth auth = new Auth();
    private Client client = new Client();
    private Debug debug = new Debug();

    @Data
    public static class Brain {
        private String httpBase = "http://localhost:8080";
        private String wsBase = "ws://localhost:8080";
    }

    @Data
    public static class Auth {
        private String tenant = "acme";
        private String username = "wile.coyote";
        private String password = "acme-rocket";
    }

    @Data
    public static class Client {
        private String version = "0.1.0";
    }

    @Data
    public static class Debug {
        private Rest rest = new Rest();
    }

    @Data
    public static class Rest {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 8766;
    }
}

