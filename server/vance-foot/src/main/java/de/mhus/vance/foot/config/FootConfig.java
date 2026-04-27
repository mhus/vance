package de.mhus.vance.foot.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.jspecify.annotations.Nullable;
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
    private History history = new History();
    private Bootstrap bootstrap = new Bootstrap();

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

    /**
     * Persistent input-history file — the list of lines the user has
     * submitted, the same set that ARROW_UP / ARROW_DOWN walks. Plain text,
     * one submitted line per file line (the {@code .bash_history} shape).
     *
     * <p>Default path when {@link #file} is {@code null}: {@code ~/.vance/foot-history}.
     * A leading {@code ~/} in {@link #file} is expanded against {@code user.home}.
     */
    @Data
    public static class History {
        private boolean enabled = true;
        private @Nullable String file;
        private int maxEntries = 500;
    }

    /**
     * Auto-bootstrap payload. Empty = no bootstrap fired on welcome.
     * If {@link #processes} is non-empty (and either {@code projectId} or
     * {@code sessionId} is set), {@code AutoBootstrapService} sends a
     * {@code session-bootstrap} after the welcome frame.
     */
    @Data
    public static class Bootstrap {
        /** Required when {@link #sessionId} is null — projectId for a new session. */
        private @Nullable String projectId;
        /** If set, resume this session instead of creating a new one. */
        private @Nullable String sessionId;
        private List<BootstrapProcess> processes = new ArrayList<>();
        /** Optional first chat message steered to the first process. */
        private @Nullable String initialMessage;
    }

    @Data
    public static class BootstrapProcess {
        /**
         * Engine name (e.g. {@code "ford"}) — one of {@code engine}
         * or {@link #recipe} must be set. If both, {@code recipe}
         * wins.
         */
        private String engine = "";

        /** Recipe name for the recipe cascade. Preferred over
         *  {@link #engine}. */
        private @Nullable String recipe;

        private String name = "";
        private @Nullable String title;
        private @Nullable String goal;

        /**
         * Engine-specific runtime parameters — see
         * {@code de.mhus.vance.api.thinkprocess.ProcessSpec#getParams()}.
         * Empty map = engine defaults.
         */
        private Map<String, Object> params = new LinkedHashMap<>();
    }
}


