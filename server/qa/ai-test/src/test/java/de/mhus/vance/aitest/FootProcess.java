package de.mhus.vance.aitest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manages the vance-foot subprocess for an ai-test. Foot runs in
 * {@code daemon} mode (no REPL) and is driven through its debug REST endpoint.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start(String)} — copies {@code foot-application-aitest.yaml}
 *       from test resources into {@code workdir/application.yaml}, then exec's
 *       {@code java -jar vance-foot.jar daemon --no-bootstrap} with
 *       {@code workdir} as the subprocess working directory and
 *       {@code workdir/foot.log} as stdout/stderr sink.</li>
 *   <li>{@link #waitForHealth(Duration)} — polls {@code GET /debug/health}.</li>
 *   <li>{@link #command(String)} — issues {@code POST /debug/command}.</li>
 *   <li>{@link #state()} — issues {@code GET /debug/state}.</li>
 *   <li>{@link #stop()} — sends {@code SIGTERM} (Process.destroy) and waits.</li>
 * </ul>
 *
 * <p>Configuration lookup:
 * <ul>
 *   <li>System property {@code vance.foot.jar} — absolute path to the foot
 *       executable jar (set by surefire from the qa/ai-test pom).</li>
 *   <li>System property {@code vance.aitest.workdir} — absolute path of the
 *       per-run working directory (typically {@code target/ai-test}).</li>
 * </ul>
 */
public final class FootProcess {

    public static final int DEBUG_PORT = 18766;

    private static final String DEBUG_BASE = "http://127.0.0.1:" + DEBUG_PORT;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private @Nullable Process process;
    private @Nullable Path workdir;

    public synchronized void start(String resourceYamlName) throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        Path jar = resolveFootJar();
        Path wd = resolveWorkdir();
        Files.createDirectories(wd);
        clearLog(wd.resolve("foot.log"));

        // Copy the foot config from test resources into the workdir as
        // application.yaml so the foot subprocess (running in this dir) picks
        // it up automatically — and we also pass --config explicitly so the
        // location is unambiguous in the subprocess logs.
        Path appYaml = wd.resolve("application.yaml");
        copyFromClasspath(resourceYamlName, appYaml);

        // Test-only logback config that routes ALL output through stdout —
        // combined with redirectErrorStream below this collapses logs,
        // ChatTerminal prints and JVM warnings into one fixed file (foot.log)
        // instead of foot's production split between vance-foot.log + stdout.
        Path logback = wd.resolve("foot-logback-aitest.xml");
        copyFromClasspath("foot-logback-aitest.xml", logback);

        ProcessBuilder pb = new ProcessBuilder(
                javaBinary(),
                "-Dlogging.config=" + logback.toAbsolutePath(),
                "-jar", jar.toAbsolutePath().toString(),
                "daemon",
                "--no-bootstrap",
                "--config", appYaml.toAbsolutePath().toString())
                .directory(wd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(wd.resolve("foot.log").toFile()));
        // Drop any inherited debug agent etc. — the subprocess starts with a
        // clean environment beyond PATH/HOME.
        Map<String, String> env = pb.environment();
        env.put("VANCE_AITEST", "1");
        Process p = pb.start();
        this.process = p;
        this.workdir = wd;
    }

    public boolean waitForHealth(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (process == null || !process.isAlive()) {
                throw new IllegalStateException("foot subprocess died before /debug/health responded");
            }
            try {
                HttpResponse<String> r = http.send(
                        HttpRequest.newBuilder(URI.create(DEBUG_BASE + "/debug/health"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return true;
                }
            } catch (IOException | InterruptedException ignored) {
                // not ready yet
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public Map<String, Object> state() throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(DEBUG_BASE + "/debug/state"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("/debug/state returned HTTP " + r.statusCode() + ": " + r.body());
        }
        return json.readValue(r.body(), Map.class);
    }

    public CommandResult command(String line) throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of("line", line));
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(DEBUG_BASE + "/debug/command"))
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("/debug/command returned HTTP " + r.statusCode() + ": " + r.body());
        }
        Map<String, Object> parsed = json.readValue(r.body(), Map.class);
        return new CommandResult(
                String.valueOf(parsed.getOrDefault("line", "")),
                Boolean.TRUE.equals(parsed.get("matched")));
    }

    public List<String> tailOutput(int limit) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(DEBUG_BASE + "/debug/output?limit=" + limit))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("/debug/output returned HTTP " + r.statusCode() + ": " + r.body());
        }
        List<Map<String, Object>> lines = json.readValue(r.body(), List.class);
        return lines.stream()
                .map(m -> String.valueOf(m.getOrDefault("text", "")))
                .toList();
    }

    public synchronized void stop() {
        Process p = process;
        if (p == null) {
            return;
        }
        if (p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        process = null;
    }

    public Path workdir() {
        Path wd = workdir;
        if (wd == null) {
            throw new IllegalStateException("FootProcess not started");
        }
        return wd;
    }

    private static Path resolveFootJar() {
        String configured = System.getProperty("vance.foot.jar");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "System property 'vance.foot.jar' is not set — surefire should pass it from the pom.");
        }
        Path jar = Path.of(configured);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "vance-foot jar not found at " + jar + " — run `mvn -pl vance-foot package` first.");
        }
        return jar;
    }

    private static Path resolveWorkdir() {
        String configured = System.getProperty("vance.aitest.workdir");
        if (configured == null || configured.isBlank()) {
            return Path.of("target", "ai-test").toAbsolutePath();
        }
        return Path.of(configured).toAbsolutePath();
    }

    private static String javaBinary() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) {
            return "java";
        }
        Path bin = Path.of(home, "bin", "java");
        return Files.isExecutable(bin) ? bin.toString() : "java";
    }

    private void copyFromClasspath(String resourceName, Path destination) throws IOException {
        try (var in = FootProcess.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IOException("Test resource not found on classpath: " + resourceName);
            }
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void clearLog(Path logFile) throws IOException {
        // The harness writes one fixed log file per process — truncate at the
        // start of every run so it always reflects the current attempt.
        if (Files.exists(logFile)) {
            Files.delete(logFile);
        }
    }

    public record CommandResult(String line, boolean matched) {}
}
