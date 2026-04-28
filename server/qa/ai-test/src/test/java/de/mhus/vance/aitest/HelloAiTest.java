package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.VanceBrainApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * First end-to-end ai-test: boots a fresh Mongo testcontainer, the brain
 * (in-process via {@code @SpringBootTest}) and the foot CLI (subprocess in
 * daemon mode), then drives foot through the debug REST endpoint to issue a
 * connect + 'Hello'. Verifies that {@code InitBrainService} seeded the
 * default tenant/users/projects and that no chat messages exist yet.
 *
 * <p>Lifecycle is per-class — Mongo, brain context and foot subprocess all
 * stand up once and tear down at the end. The single test method is
 * deliberately fat; this is integration scaffolding, not a unit test.
 *
 * <h2>Where things land</h2>
 * <ul>
 *   <li>Mongo container — host port 47017 (fixed, non-default).</li>
 *   <li>Brain — in-process Spring context, HTTP on 18080, log to
 *       {@code target/ai-test/brain.log}.</li>
 *   <li>Foot — subprocess, debug REST on 18766, working dir
 *       {@code target/ai-test}, log to {@code target/ai-test/foot.log}.</li>
 * </ul>
 *
 * <p>The Gemini API key is loaded from {@code confidential/init-settings.yaml}
 * by the brain's {@code InitSettingsLoader}, which walks parent directories
 * from the test working dir until it finds the file.
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelloAiTest {

    private static final FootProcess foot = new FootProcess();

    @Autowired
    private MongoTemplate mongo;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        // Start the testcontainer eagerly so the URI is known before the
        // Spring context binds spring.mongodb.* properties.
        MongoFixture.start();
        registry.add("spring.mongodb.uri", MongoFixture::uri);
        registry.add("spring.mongodb.database", () -> MongoFixture.DATABASE);
    }

    @BeforeAll
    void startFoot() throws Exception {
        // Mongo container and brain context are both fresh per JVM, so the
        // DB is empty when InitBrainService's PostConstruct runs and the
        // seed data lands as expected — no manual drop needed here.
        foot.start("foot-application-aitest.yaml");
        boolean up = foot.waitForHealth(Duration.ofSeconds(60));
        assertThat(up)
                .as("foot subprocess should expose /debug/health within 60s — "
                        + "see " + foot.workdir().resolve("foot.log") + " on failure")
                .isTrue();
    }

    @AfterAll
    void stopFoot() {
        foot.stop();
    }

    @Test
    void connectAndSendHello() throws Exception {
        // 1. /connect — opens the WebSocket from foot to brain.
        FootProcess.CommandResult connectRes = foot.command("/connect");
        assertThat(connectRes.matched())
                .as("/connect should resolve to a known slash command")
                .isTrue();

        // The connect handshake is async; poll /debug/state until the WS is
        // open or we time out. 30s is generous for a localhost handshake but
        // forgiving on a cold JVM.
        boolean connected = pollUntil(Duration.ofSeconds(30),
                () -> Boolean.TRUE.equals(foot.state().get("connectionOpen")));
        assertThat(connected)
                .as("foot WebSocket should reach connectionOpen=true within 30s")
                .isTrue();

        // 2. Send 'Hello' through the chat path (no slash routing). The test
        //    has not bootstrapped a session, so ChatInputService rejects with
        //    'No bound session ...' — but the request goes all the way through
        //    the same code path the human REPL uses, so {ok,error} reflect
        //    the real state. A follow-up test that bootstraps a session and
        //    a process will see ok=true and brain-side chat_messages > 0.
        FootProcess.InputResult helloRes = foot.chat("Hello");
        assertThat(helloRes.kind()).isEqualTo("CHAT");
        assertThat(helloRes.line()).isEqualTo("Hello");
        assertThat(helloRes.ok()).isFalse();
        assertThat(helloRes.error()).contains("No bound session");

        // 3. Verify InitBrainService seed data exists in Mongo.
        long tenants = mongo.getCollection("tenants").countDocuments();
        long users = mongo.getCollection("users").countDocuments();
        long groups = mongo.getCollection("project_groups").countDocuments();
        long projects = mongo.getCollection("projects").countDocuments();
        long teams = mongo.getCollection("teams").countDocuments();
        long settings = mongo.getCollection("settings").countDocuments();

        assertThat(tenants)
                .as("tenant collection should contain at least 'acme' "
                        + "(plus optionally 'default' from TenantService)")
                .isGreaterThanOrEqualTo(1);
        assertThat(users)
                .as("InitBrainService seeds 3 acme users (marvin/wile/road)")
                .isGreaterThanOrEqualTo(3);
        assertThat(groups)
                .as("InitBrainService seeds 4 project groups under acme")
                .isGreaterThanOrEqualTo(4);
        assertThat(projects)
                .as("InitBrainService seeds 8 projects under acme")
                .isGreaterThanOrEqualTo(8);
        assertThat(teams)
                .as("InitBrainService seeds 2 acme teams")
                .isGreaterThanOrEqualTo(2);
        assertThat(settings)
                .as("InitSettingsLoader should have applied at least the gemini key from confidential/init-settings.yaml")
                .isGreaterThanOrEqualTo(1);

        // 4. No chat messages exist on a fresh DB — the test never created a
        //    session/process, so the chat_messages collection should be empty.
        long chatMessages = mongo.getCollection("chat_messages").countDocuments();
        assertThat(chatMessages)
                .as("chat_messages should be empty on a fresh DB run")
                .isZero();

        // 5. Sanity: confirm log files exist.
        Path brainLog = Path.of("target", "ai-test", "brain.log").toAbsolutePath();
        Path footLog = foot.workdir().resolve("foot.log");
        assertThat(Files.exists(brainLog)).as("brain.log present at " + brainLog).isTrue();
        assertThat(Files.exists(footLog)).as("foot.log present at " + footLog).isTrue();
    }

    private interface BoolSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private static boolean pollUntil(Duration timeout, BoolSupplier check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
