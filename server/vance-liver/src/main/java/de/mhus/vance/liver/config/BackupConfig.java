package de.mhus.vance.liver.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Mirrors the YAML structure of {@code ~/.vancetope/liver/backup.yaml}
 * ({@code planning/user-backup.md} §4.3).
 *
 * <p>The defaults baked into the field initializers match the planning
 * document: include is {@code ["*"]}, exclude is empty,
 * {@code includeDisabled} is false, deny contains {@code ["_vance/**"]},
 * the three retention buckets keep their hour/day/week split. A config
 * file that omits any of these blocks still produces a usable run.
 *
 * <p>Validation (URL parse, token presence, deny-glob sanity) happens
 * in {@link ConfigLoader} after deserialization —
 * {@link BackupConfig} is a pure data shape.
 *
 * <p>Server connection shape: {@code server.url} carries the full
 * brain URL (scheme + host + optional port, no path) and
 * {@code server.tenant} names the tenant. The token is the raw JWT
 * minted via {@code POST /integration-tokens} (with
 * {@code scopeProfiles: ["backup"]}). This replaces the earlier
 * placeholder {@code connection: vancetope1.…} — the placeholder
 * locked the client to {@code https} and hid the host/tenant split.
 * With explicit fields, the user writes {@code http://localhost:9991}
 * for a local dev brain and {@code https://brain.example} for
 * production; no heuristic needed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BackupConfig {

    @JsonProperty("server")
    public Server server = new Server();

    /** Signed JWT from {@code POST /integration-tokens}. Inline; for sharing configs without secrets use {@link #tokenFile}. */
    @JsonProperty("token")
    public @Nullable String token;

    /** Alternative to {@link #token} — file with the raw JWT on its own line. Read once at load time. */
    @JsonProperty("tokenFile")
    public @Nullable String tokenFile;

    /** Where snapshots live. Defaults to {@code ~/VancetopeBackup}. */
    @JsonProperty("target")
    public @Nullable String target;

    @JsonProperty("projects")
    public Projects projects = new Projects();

    /** Globs that apply to every project (planning §4.3 + §6.3 default {@code _vance/**}). */
    @JsonProperty("deny")
    public List<String> deny = List.of("_vance/**");

    /** Per-project deny: project-name → list of globs. */
    @JsonProperty("projectDeny")
    public Map<String, List<String>> projectDeny = new LinkedHashMap<>();

    @JsonProperty("retention")
    public Retention retention = new Retention();

    /** Show a system notification on failure or token expiry (planning §4.3). */
    @JsonProperty("notify")
    public boolean notify = true;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Server {
        /**
         * Brain URL with scheme, host, and optional port. Examples:
         * {@code https://brain.example} or {@code http://localhost:9991}.
         * No path component — the backup routes are appended as
         * {@code /brain/{tenant}/backup/...}.
         */
        @JsonProperty("url")
        public @Nullable String url;

        @JsonProperty("tenant")
        public @Nullable String tenant;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Projects {
        @JsonProperty("include")
        public List<String> include = List.of("*");

        @JsonProperty("exclude")
        public List<String> exclude = List.of();

        /** Disabled projects are skipped unless listed in {@code include} or this flag is set. */
        @JsonProperty("includeDisabled")
        public boolean includeDisabled = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Retention {
        @JsonProperty("hourly")
        public int hourly = 24;

        @JsonProperty("daily")
        public int daily = 30;

        @JsonProperty("weekly")
        public int weekly = 52;
    }
}
