package de.mhus.vance.anus.compose;

import de.mhus.vance.anus.BuildInfo;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Standalone terminal wizard for {@code anus --setup-docker-compose}.
 *
 * <p>Runs entirely outside Spring (no MongoDB) — see
 * {@link DockerComposeSetupBootstrap}. It scaffolds a Docker Compose environment
 * into a host-mounted volume so a user with nothing but Docker installed can go
 * from {@code docker run … --setup-docker-compose} to a running Vance stack
 * without cloning anything.
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve the output directory ({@code /data} when mounted, else the CWD).</li>
 *   <li>Load an existing {@code .env} if present and pre-fill the state.</li>
 *   <li>Generate any missing secrets (encryption password, internal token,
 *       Mongo password).</li>
 *   <li>Drive an edit menu (basic entries always; expert entries behind the
 *       expert-mode gate) until {@code Save} or {@code Quit}.</li>
 *   <li>On save: write {@code .env} (merged) + {@code docker-compose.yml} and
 *       print the next steps.</li>
 * </ol>
 */
public final class DockerComposeSetupWizard {

    /** Preset languages (name, ISO code). "Other" lets the user type a custom pair. */
    private static final List<String[]> LANGUAGES = List.of(
            new String[] {"English", "en"},
            new String[] {"German", "de"},
            new String[] {"French", "fr"},
            new String[] {"Spanish", "es"},
            new String[] {"Italian", "it"},
            new String[] {"Portuguese", "pt"},
            new String[] {"Dutch", "nl"});

    private final SecretGenerator secrets = new SecretGenerator();

    private DockerComposeSetupWizard() {}

    /** Entry point from {@code VanceAnusApplication.main}. Returns the process exit code. */
    public static int run() {
        return new DockerComposeSetupWizard().execute();
    }

    private int execute() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            PrintWriter out = terminal.writer();
            return drive(out, reader);
        } catch (IOException e) {
            System.err.println("anus: cannot open terminal — " + e.getMessage());
            return 1;
        }
    }

    private int drive(PrintWriter out, LineReader reader) {
        Path dir = resolveOutputDir();
        Path envPath = dir.resolve(".env");
        Path composePath = dir.resolve("docker-compose.yml");

        out.println();
        out.println("Vance — Docker Compose Setup");
        out.println("============================");
        out.printf("%s%n", BuildInfo.line());
        out.println();
        out.printf("Output directory: %s%n", dir.toAbsolutePath());
        out.flush();

        ComposeSetupState state = new ComposeSetupState();
        Map<String, String> existingEnv;
        try {
            existingEnv = DotEnvFile.read(envPath);
        } catch (IOException e) {
            out.println("Cannot read existing .env: " + e.getMessage());
            out.flush();
            return 1;
        }
        if (!existingEnv.isEmpty()) {
            prefillFromEnv(state, existingEnv);
            state.setLoadedExisting(true);
            out.println("Found an existing .env — values pre-filled below.");
        }
        if (Files.exists(composePath)) {
            out.println("Note: docker-compose.yml exists and will be regenerated on save.");
        }
        ensureSecrets(state);
        out.println();
        out.flush();

        if (!menu(out, reader, state)) {
            // Quit ('q') / Ctrl-C / EOF: nothing written. Exit non-zero so a
            // wrapping script can tell an aborted run from a successful save.
            out.println("Cancelled. No files written.");
            out.flush();
            return 1;
        }

        Path caddyPath = dir.resolve("Caddyfile");
        Path readmePath = dir.resolve("README.md");
        try {
            Files.createDirectories(dir);
            Map<String, String> managed = ComposeFileRenderer.renderEnv(state);
            Files.writeString(envPath, DotEnvFile.render(managed, existingEnv),
                    StandardCharsets.UTF_8);
            Files.writeString(composePath, ComposeFileRenderer.renderCompose(state),
                    StandardCharsets.UTF_8);
            Files.writeString(caddyPath, ComposeFileRenderer.renderCaddyfile(),
                    StandardCharsets.UTF_8);
            Files.writeString(readmePath, ComposeFileRenderer.renderReadme(state),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            out.println("Write failed: " + e.getMessage());
            out.flush();
            return 1;
        }

        printDone(out, state, envPath, composePath, readmePath, caddyPath);
        return 0;
    }

    // ──────────────────────── menu ────────────────────────

    /** @return {@code true} when the user chose Save. */
    private boolean menu(PrintWriter out, LineReader reader, ComposeSetupState s) {
        while (true) {
            out.println("Settings");
            out.println("--------");
            out.printf("   1) Default language:     %s (%s)%n", s.getLanguageName(), s.getLanguageCode());
            out.printf("   2) Anus login password:  %s%n",
                    s.getAnusPasswordHash().isBlank() ? "(none — REPL open)" : "(set)");
            out.printf("   3) Secret encryption pw: %s%n", mask(s.getEncryptionPassword()));
            out.printf("   4) Analysis (Fook):      %s%n", s.isFookEnabled() ? "enabled" : "disabled");
            out.printf("   5) Access mode:          %s%n", s.isExternalAccess() ? "external URL" : "local (localhost)");
            if (s.isExternalAccess()) {
                out.printf("   6)   External URL:       %s%n",
                        s.getExternalUrl().isBlank() ? "(not set — required!)" : s.getExternalUrl());
                out.printf("   7)   TLS:                %s%n",
                        s.isCaddyTls() ? "bundled Caddy (auto-HTTPS)" : "HTTP only (upstream does TLS)");
            }
            out.printf("   8) Vance port:           http://localhost:%d%n", s.getFacePort());
            out.printf("   9) Expert mode:          %s%n", s.isExpertMode() ? "on" : "off");
            if (s.isExpertMode()) {
                out.printf("  10) Redis (live):         %s%n", s.isRedisEnabled() ? "enabled" : "disabled");
                out.printf("  11) Debug tools (UIs):    %s%n", s.isToolsEnabled() ? "enabled" : "disabled");
                out.printf("  12) Anus admin service:   %s%n", s.isAnusServiceEnabled() ? "enabled" : "disabled");
                out.printf("  13) Expose Brain port:    %s%n", exposeLabel(s.isExposeBrainPort(), s.getBrainPort()));
                out.printf("  14) Expose Mongo port:    %s%n", exposeLabel(s.isExposeMongoPort(), s.getMongoPort()));
                out.printf("  15) Expose Redis port:    %s%n", exposeLabel(s.isExposeRedisPort(), s.getRedisPort()));
                out.printf("  16) Image tag:            %s%n", s.getImageTag());
                out.printf("  17) MongoDB password:     %s%n", mask(s.getMongoPassword()));
            }
            out.println();
            out.flush();

            String in = readLine(reader, "Edit a number, s) Save, q) Quit: ");
            if (in == null) {
                return false;
            }
            switch (in.strip().toLowerCase()) {
                case "s" -> { return true; }
                case "q" -> { return false; }
                case "1" -> editLanguage(out, reader, s);
                case "2" -> editAnusPassword(out, reader, s);
                case "3" -> editEncryptionPassword(out, reader, s);
                case "4" -> s.setFookEnabled(!s.isFookEnabled());
                case "5" -> s.setExternalAccess(!s.isExternalAccess());
                case "6" -> { if (s.isExternalAccess()) editExternalUrl(out, reader, s); }
                case "7" -> { if (s.isExternalAccess()) s.setCaddyTls(!s.isCaddyTls()); }
                case "8" -> editVancePort(out, reader, s);
                case "9" -> s.setExpertMode(!s.isExpertMode());
                case "10" -> { if (s.isExpertMode()) s.setRedisEnabled(!s.isRedisEnabled()); }
                case "11" -> { if (s.isExpertMode()) s.setToolsEnabled(!s.isToolsEnabled()); }
                case "12" -> { if (s.isExpertMode()) s.setAnusServiceEnabled(!s.isAnusServiceEnabled()); }
                case "13" -> { if (s.isExpertMode()) s.setExposeBrainPort(!s.isExposeBrainPort()); }
                case "14" -> { if (s.isExpertMode()) s.setExposeMongoPort(!s.isExposeMongoPort()); }
                case "15" -> { if (s.isExpertMode()) s.setExposeRedisPort(!s.isExposeRedisPort()); }
                case "16" -> { if (s.isExpertMode()) editImageTag(out, reader, s); }
                case "17" -> { if (s.isExpertMode()) editMongoPassword(out, reader, s); }
                default -> out.println("Unknown choice.");
            }
            out.println();
            out.flush();
        }
    }

    private static String exposeLabel(boolean exposed, int port) {
        return exposed ? "yes → host " + port : "no (internal only)";
    }

    private void editExternalUrl(PrintWriter out, LineReader reader, ComposeSetupState s) {
        out.println("Browser-facing URL, e.g. https://vance.example.de");
        out.println("(https → Caddy can auto-provision TLS and cookies get the Secure flag;");
        out.println(" an http:// URL is fine when an upstream like ngrok terminates TLS).");
        out.flush();
        String v = readLine(reader, "External URL: ");
        if (v != null && !v.isBlank()) {
            s.setExternalUrl(v.strip());
        }
    }

    private void editVancePort(PrintWriter out, LineReader reader, ComposeSetupState s) {
        out.println("Host port Vance is reachable on (http://localhost:<port>).");
        out.flush();
        String v = readLine(reader, "Vance port [" + s.getFacePort() + "]: ");
        Integer port = parseInt(v);
        if (port != null && port > 0 && port < 65536) {
            s.setFacePort(port);
        } else if (v != null && !v.isBlank()) {
            out.println("Invalid port — unchanged.");
        }
    }

    private void editLanguage(PrintWriter out, LineReader reader, ComposeSetupState s) {
        out.println("Languages:");
        for (int i = 0; i < LANGUAGES.size(); i++) {
            out.printf("  %d) %s (%s)%n", i + 1, LANGUAGES.get(i)[0], LANGUAGES.get(i)[1]);
        }
        out.printf("  %d) Other (type name + code)%n", LANGUAGES.size() + 1);
        out.flush();
        String in = readLine(reader, "Pick language: ");
        Integer pick = parseInt(in);
        if (pick == null || pick < 1 || pick > LANGUAGES.size() + 1) {
            out.println("Invalid choice.");
            return;
        }
        if (pick <= LANGUAGES.size()) {
            String[] lang = LANGUAGES.get(pick - 1);
            s.setLanguageName(lang[0]);
            s.setLanguageCode(lang[1]);
            return;
        }
        String name = readLine(reader, "Language name (e.g. Polish): ");
        String code = readLine(reader, "Language code (e.g. pl): ");
        if (name != null && !name.isBlank() && code != null && !code.isBlank()) {
            s.setLanguageName(name.strip());
            s.setLanguageCode(code.strip().toLowerCase());
        } else {
            out.println("Name and code required — keeping previous.");
        }
    }

    private void editAnusPassword(PrintWriter out, LineReader reader, ComposeSetupState s) {
        out.println("Anus admin-shell login password (blank = no password gate).");
        out.flush();
        String pw = readPassword(reader, "Password: ");
        if (pw.isBlank()) {
            s.setAnusPasswordHash("");
            out.println("Login gate disabled.");
            return;
        }
        String confirm = readPassword(reader, "Confirm: ");
        if (!pw.equals(confirm)) {
            out.println("Mismatch — unchanged.");
            return;
        }
        s.setAnusPasswordHash(secrets.bcrypt(pw));
        out.println("Password hash generated.");
    }

    private void editEncryptionPassword(PrintWriter out, LineReader reader, ComposeSetupState s) {
        out.println("Secret-settings encryption password. Blank keeps the current one;");
        out.println("type 'g' to generate a fresh random value.");
        out.flush();
        String pw = readPassword(reader, "Encryption password (blank=keep, g=generate): ");
        if (pw.isBlank()) {
            return;
        }
        if ("g".equals(pw)) {
            s.setEncryptionPassword(secrets.token(24));
            out.println("Generated.");
            return;
        }
        s.setEncryptionPassword(pw);
    }

    private void editImageTag(PrintWriter out, LineReader reader, ComposeSetupState s) {
        String v = readLine(reader, "Image tag (e.g. latest, 1.0.0): ");
        if (v != null && !v.isBlank()) {
            s.setImageTag(v.strip());
        }
    }

    private void editMongoPassword(PrintWriter out, LineReader reader, ComposeSetupState s) {
        String pw = readPassword(reader, "MongoDB root password (blank=keep, g=generate): ");
        if (pw.isBlank()) {
            return;
        }
        if ("g".equals(pw)) {
            s.setMongoPassword(secrets.token(18));
            out.println("Generated.");
            return;
        }
        s.setMongoPassword(pw);
    }

    // ──────────────────────── helpers ────────────────────────

    private Path resolveOutputDir() {
        String override = System.getenv("VANCE_COMPOSE_OUT_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path data = Path.of("/data");
        if (Files.isDirectory(data)) {
            return data;
        }
        return Path.of(".");
    }

    private void prefillFromEnv(ComposeSetupState s, Map<String, String> env) {
        env.forEach((k, v) -> {
            switch (k) {
                case "VANCE_IMAGE_NAMESPACE" -> s.setImageNamespace(v);
                case "IMAGE_TAG" -> s.setImageTag(v);
                case "MONGO_INITDB_ROOT_USERNAME" -> s.setMongoUser(v);
                case "MONGO_INITDB_ROOT_PASSWORD" -> s.setMongoPassword(v);
                case "VANCE_MONGODB_DATABASE" -> s.setMongoDatabase(v);
                case "MONGO_PORT" -> s.setMongoPort(intOr(v, s.getMongoPort()));
                case "VANCE_ENCRYPTION_PASSWORD" -> s.setEncryptionPassword(v);
                case "VANCE_INTERNAL_TOKEN" -> s.setInternalToken(v);
                case "VANCE_FOOK_ENABLED" -> s.setFookEnabled(Boolean.parseBoolean(v));
                case "BRAIN_PORT" -> s.setBrainPort(intOr(v, s.getBrainPort()));
                case "VANCE_DEFAULT_LANGUAGE" -> s.setLanguageName(v);
                case "VANCE_DEFAULT_LANGUAGE_CODE" -> s.setLanguageCode(v);
                case "VANCE_REDIS_ENABLED" -> s.setRedisEnabled(Boolean.parseBoolean(v));
                case "REDIS_PORT" -> s.setRedisPort(intOr(v, s.getRedisPort()));
                case "VANCE_PORT", "FACE_PORT" -> s.setFacePort(intOr(v, s.getFacePort()));
                case "VANCE_ACCESS_MODE" -> s.setExternalAccess("external".equalsIgnoreCase(v));
                case "VANCE_EXTERNAL_URL" -> s.setExternalUrl(v);
                case "VANCE_CADDY_TLS" -> s.setCaddyTls(!"off".equalsIgnoreCase(v));
                case "VANCE_EXPOSE_BRAIN" -> s.setExposeBrainPort(Boolean.parseBoolean(v));
                case "VANCE_EXPOSE_MONGO" -> s.setExposeMongoPort(Boolean.parseBoolean(v));
                case "VANCE_EXPOSE_REDIS" -> s.setExposeRedisPort(Boolean.parseBoolean(v));
                case "VANCE_ANUS_SERVICE" -> s.setAnusServiceEnabled(Boolean.parseBoolean(v));
                case "VANCE_ANUS_PASSWORD_HASH" -> s.setAnusPasswordHash(v);
                case "MONGO_EXPRESS_USERNAME" -> { s.setToolsEnabled(true); s.setMongoExpressUser(v); }
                case "MONGO_EXPRESS_PASSWORD" -> s.setMongoExpressPassword(v);
                case "MONGO_EXPRESS_PORT" -> s.setMongoExpressPort(intOr(v, s.getMongoExpressPort()));
                case "REDIS_UI_PORT" -> s.setRedisUiPort(intOr(v, s.getRedisUiPort()));
                default -> { /* unmanaged — carried over verbatim on write */ }
            }
        });
    }

    /** Fills any still-empty secret with a fresh random value (first-run case). */
    private void ensureSecrets(ComposeSetupState s) {
        if (s.getEncryptionPassword().isBlank()) {
            s.setEncryptionPassword(secrets.token(24));
        }
        if (s.getInternalToken().isBlank()) {
            s.setInternalToken(secrets.token(24));
        }
        if (s.getMongoPassword().isBlank()) {
            s.setMongoPassword(secrets.token(18));
        }
    }

    private void printDone(PrintWriter out, ComposeSetupState s, Path envPath, Path composePath,
            Path readmePath, Path caddyPath) {
        String url = s.isExternalAccess() && !s.getExternalUrl().isBlank()
                ? s.getExternalUrl()
                : "http://localhost:" + s.getFacePort();
        out.println();
        out.println("Wrote:");
        out.printf("  - %s%n", composePath.toAbsolutePath());
        out.printf("  - %s%n", envPath.toAbsolutePath());
        out.printf("  - %s%n", caddyPath.toAbsolutePath());
        out.printf("  - %s%n", readmePath.toAbsolutePath());
        out.println();
        out.println("Next steps (from the directory containing these files):");
        out.println("  1) Start the stack:");
        out.println("       docker compose up -d");
        out.println("  2) First-time setup (tenant + user + LLM) — see README.md for the");
        out.println("     one-shot admin-container command.");
        out.println("  3) Open Vance:");
        out.printf("       %s%n", url);
        if (s.isExternalAccess() && s.isCaddyTls()) {
            out.println();
            out.println("External access: the bundled Caddy publishes ports 80 + 443 and");
            out.printf("  auto-provisions a TLS certificate for %s — make sure both ports%n",
                    ComposeFileRenderer.externalHost(s));
            out.println("  are reachable from the public internet (port-forwarding / DNS).");
        } else if (s.isExternalAccess()) {
            out.println();
            out.printf("External access (HTTP only): Vance listens plain HTTP on port %d.%n", s.getFacePort());
            out.println("  Point your TLS terminator (ngrok, Cloudflare Tunnel, external LB) at it.");
        }
        if (s.isToolsEnabled()) {
            out.println();
            out.println("Debug tools (start with):  docker compose --profile tools up -d");
            out.printf("  mongo-express:   http://localhost:%d%n", s.getMongoExpressPort());
            if (s.isRedisEnabled()) {
                out.printf("  redis-commander: http://localhost:%d%n", s.getRedisUiPort());
            }
        }
        out.println();
        out.flush();
    }

    private static String mask(String value) {
        return value.isBlank() ? "(not set)" : "*".repeat(Math.min(value.length(), 8));
    }

    private static @Nullable String readLine(LineReader reader, String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        }
    }

    private static String readPassword(LineReader reader, String prompt) {
        try {
            String s = reader.readLine(prompt, '*');
            return s == null ? "" : s;
        } catch (UserInterruptException | EndOfFileException e) {
            return "";
        }
    }

    private static @Nullable Integer parseInt(@Nullable String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(String s, int fallback) {
        Integer v = parseInt(s);
        return v == null ? fallback : v;
    }
}
