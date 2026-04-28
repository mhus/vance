package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import de.mhus.vance.brain.VanceBrainApplication;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end test for the Vance hub mechanics:
 *
 * <ol>
 *   <li>Login via {@code /connect} fires {@code HomeBootstrapService.ensureHome},
 *       which creates the {@code _home} project group and the per-user
 *       {@code _user_<login>} project with {@code kind=SYSTEM}.</li>
 *   <li>{@code /session-create _user_wile.coyote} starts a session whose
 *       chat-process runs the {@code vance} engine (not Arthur), driven by
 *       {@code SessionChatBootstrapper}'s SYSTEM-project branch.</li>
 *   <li>{@code VanceEngine.start} writes the Vance-flavoured greeting and
 *       (if peer activity exists) appends a recap line.</li>
 * </ol>
 *
 * <p>This is the integration counterpart to the Phase 1 + 2 changes
 * (ProjectKind, HomeBootstrapService, AccessController hook,
 * SessionChatBootstrapper hub-branch, VanceEngine + bundledConfig).
 *
 * <p>Lifecycle parallels {@link HelloAiTest}: per-class brain context,
 * shared Mongo container (cross-test), foot subprocess in daemon mode.
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VanceHubTest {

    private static final FootProcess foot = new FootProcess();

    private static final String TENANT = "acme";
    private static final String USER_LOGIN = "wile.coyote";
    private static final String HUB_GROUP = "_home";
    private static final String HUB_PROJECT = "_user_" + USER_LOGIN;
    private static final String VANCE_ENGINE_NAME = "vance";

    @Autowired
    private MongoTemplate mongo;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        MongoFixture.start();
        registry.add("spring.mongodb.uri", MongoFixture::uri);
        registry.add("spring.mongodb.database", () -> MongoFixture.DATABASE);
    }

    @BeforeAll
    void startFoot() throws Exception {
        foot.start("foot-application-aitest.yaml");
        boolean up = foot.waitForHealth(Duration.ofSeconds(60));
        assertThat(up)
                .as("foot should expose /debug/health within 60s — see "
                        + foot.workdir().resolve("foot.log"))
                .isTrue();
    }

    @AfterAll
    void stopFoot() {
        foot.stop();
    }

    @Test
    @Order(1)
    void hubProjectExistsAfterConnect_andSessionCreateUsesVanceEngine()
            throws Exception {
        // ─── 1. /connect — login mints a JWT, which triggers
        //         HomeBootstrapService.ensureHome(...). Idempotent.
        FootProcess.CommandResult connect = foot.command("/connect");
        assertThat(connect.matched())
                .as("/connect should resolve to a known slash command")
                .isTrue();
        boolean opened = pollUntil(Duration.ofSeconds(30),
                () -> Boolean.TRUE.equals(foot.state().get("connectionOpen")));
        assertThat(opened)
                .as("foot WebSocket should reach connectionOpen=true within 30s")
                .isTrue();

        // ─── 2. Hub project group + per-user hub project must exist now.
        //         AccessController.createToken() runs ensureHome on every
        //         successful login, so the structures are guaranteed.
        Document group = findOne("project_groups",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", HUB_GROUP)));
        assertThat(group)
                .as("home project group '%s' should exist after login", HUB_GROUP)
                .isNotNull();

        Document hubProject = findOne("projects",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", HUB_PROJECT)));
        assertThat(hubProject)
                .as("per-user hub project '%s' should exist after login", HUB_PROJECT)
                .isNotNull();
        assertThat(hubProject.getString("kind"))
                .as("hub project must carry kind=SYSTEM")
                .isEqualTo("SYSTEM");
        assertThat(hubProject.getString("projectGroupId"))
                .as("hub project must be parented to '%s'", HUB_GROUP)
                .isEqualTo(HUB_GROUP);

        // No regular user project should ever be SYSTEM (sanity check on
        // the seed data + ProjectKind default).
        List<Document> systemProjects = findAll("projects",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("kind", "SYSTEM")));
        assertThat(systemProjects)
                .as("only the per-user hub project may be SYSTEM in the seed")
                .hasSize(1)
                .extracting(d -> d.getString("name"))
                .containsExactly(HUB_PROJECT);

        // ─── 3. Open a session in the hub project.
        FootProcess.CommandResult create = foot.command("/session-create " + HUB_PROJECT);
        assertThat(create.matched())
                .as("/session-create should resolve to a known slash command")
                .isTrue();

        // The session-create flow runs SessionChatBootstrapper which
        // detects kind=SYSTEM and starts the 'vance' engine. Wait until
        // the hub-process row appears in Mongo.
        boolean vanceProcessAppeared = pollUntil(Duration.ofSeconds(15), () -> {
            Document p = findOne("think_processes",
                    Filters.and(
                            Filters.eq("tenantId", TENANT),
                            Filters.eq("thinkEngine", VANCE_ENGINE_NAME)));
            return p != null;
        });
        assertThat(vanceProcessAppeared)
                .as("a think-process with engine=vance should appear within 15s")
                .isTrue();

        Document vanceProcess = findOne("think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", VANCE_ENGINE_NAME)));
        assertThat(vanceProcess).isNotNull();
        assertThat(vanceProcess.getString("name"))
                .as("the bootstrapper names the chat-process 'chat'")
                .isEqualTo("chat");
        // Bundled-config path means recipeName stays null (no recipe used).
        assertThat(vanceProcess.getString("recipeName"))
                .as("vance bypasses recipes — recipeName must be null")
                .isNull();
        // Vance's bundled config writes its own promptOverride from the
        // classpath resource. Length isn't pinned here — we just assert
        // it's substantial and contains the persona keyword.
        String prompt = vanceProcess.getString("promptOverride");
        assertThat(prompt)
                .as("vance promptOverride should come from vance-prompt.md")
                .isNotNull()
                .contains("Vance");

        String vanceProcessId = vanceProcess.getObjectId("_id").toHexString();

        // ─── 4. The greeting Vance.start() wrote should be in chat_messages.
        boolean greetingArrived = pollUntil(Duration.ofSeconds(10), () -> {
            Document msg = findOne("chat_messages",
                    Filters.eq("thinkProcessId", vanceProcessId));
            return msg != null;
        });
        assertThat(greetingArrived)
                .as("VanceEngine.start() should append a greeting within 10s")
                .isTrue();

        Document greeting = findOne("chat_messages",
                Filters.eq("thinkProcessId", vanceProcessId));
        assertThat(greeting).isNotNull();
        assertThat(greeting.getString("role"))
                .as("greeting role should be ASSISTANT")
                .isEqualTo("ASSISTANT");
        assertThat(greeting.getString("content"))
                .as("greeting content should match VanceEngine.GREETING")
                .startsWith("Hi, I'm Vance");
    }

    /**
     * End-to-end chat round-trip: with the hub session bound (from the
     * previous test method), send a real chat message via
     * {@code POST /debug/chat} and assert that VanceEngine produced an
     * assistant reply persisted to {@code chat_messages}. Exercises the
     * full LLM pipeline including the bundled prompt — so this test
     * needs a working Gemini key in {@code confidential/init-settings.yaml}.
     */
    @Test
    @Order(2)
    void chatPathProducesAssistantReply() throws Exception {
        Document vanceProcess = findOne("think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", VANCE_ENGINE_NAME)));
        assertThat(vanceProcess)
                .as("test 1 should have left a vance chat-process behind")
                .isNotNull();
        String vanceProcessId = vanceProcess.getObjectId("_id").toHexString();

        long messagesBefore = mongo.getCollection("chat_messages")
                .countDocuments(Filters.eq("thinkProcessId", vanceProcessId));

        // Send a deliberately easy question — the LLM might pick a
        // tool or might just answer; both outcomes are fine. We only
        // assert that an ASSISTANT reply lands.
        FootProcess.InputResult chat = foot.chat("Hi, kurze Frage: was kannst du eigentlich?");
        assertThat(chat.ok())
                .as("/debug/chat should succeed (foot has a bound session "
                        + "with an active process from the previous test). "
                        + "Error: %s", chat.error())
                .isTrue();

        // Wait until the assistant reply lands. Generous timeout — first
        // LLM call after Gemini cold-start can take a few seconds.
        boolean replyArrived = pollUntil(Duration.ofSeconds(60), () -> {
            long count = mongo.getCollection("chat_messages")
                    .countDocuments(Filters.and(
                            Filters.eq("thinkProcessId", vanceProcessId),
                            Filters.eq("role", "ASSISTANT")));
            // greeting was already 1 ASSISTANT message; reply is the next one
            return count >= 2;
        });
        assertThat(replyArrived)
                .as("VanceEngine should have produced an assistant reply within 60s")
                .isTrue();

        // Sanity: the user message we just sent should also be in chat_messages.
        Document userMsg = mongo.getCollection("chat_messages")
                .find(Filters.and(
                        Filters.eq("thinkProcessId", vanceProcessId),
                        Filters.eq("role", "USER")))
                .sort(Sorts.descending("createdAt"))
                .first();
        assertThat(userMsg)
                .as("the user message we sent via /debug/chat should be persisted")
                .isNotNull();
        assertThat(userMsg.getString("content"))
                .as("user message content")
                .contains("was kannst du eigentlich");

        long messagesAfter = mongo.getCollection("chat_messages")
                .countDocuments(Filters.eq("thinkProcessId", vanceProcessId));
        assertThat(messagesAfter)
                .as("at least USER + ASSISTANT messages added on top of the greeting")
                .isGreaterThanOrEqualTo(messagesBefore + 2);
    }

    /**
     * Tool-call round-trip: Vance gets a deliberately unambiguous "leg
     * ein Projekt {name} an"-instruction so the LLM picks
     * {@code project_create} reliably. We then assert that the project,
     * its session, and an Arthur chat-process with cross-project parent
     * = the Vance hub-process all materialise in Mongo.
     *
     * <p>LLMs are stochastic, so the prompt is engineered for high tool-
     * call reliability:
     * <ul>
     *   <li>Direct imperative ("leg an", not "könntest du").</li>
     *   <li>Concrete name in backticks so the model passes it through
     *       verbatim.</li>
     *   <li>No initialPrompt asked for — keeps the call shape minimal.</li>
     * </ul>
     */
    @Test
    @Order(3)
    void chatTriggersProjectCreate() throws Exception {
        Document vanceProcess = findOne("think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", VANCE_ENGINE_NAME)));
        assertThat(vanceProcess)
                .as("test 1 should have left a vance chat-process behind")
                .isNotNull();
        String vanceProcessId = vanceProcess.getObjectId("_id").toHexString();

        // A name unlikely to collide with the InitBrain seed projects.
        String requestedProject = "apollo-13-audit";

        // Make sure it doesn't already exist (previous test re-run via
        // shared mongo container — best-effort cleanup).
        mongo.getCollection("projects").deleteMany(
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", requestedProject)));

        FootProcess.InputResult chat = foot.chat(
                "Bitte leg ein neues Projekt mit dem exakten Namen `"
                        + requestedProject + "` an. Keinen initialPrompt, "
                        + "keine zusätzlichen Schritte — einfach nur das "
                        + "Projekt anlegen.");
        assertThat(chat.ok())
                .as("/debug/chat should succeed; error: %s", chat.error())
                .isTrue();

        // Wait for the project document to appear. 90 seconds gives the
        // LLM room for two tool iterations + final synthesis.
        boolean projectAppeared = pollUntil(Duration.ofSeconds(90), () -> {
            Document p = findOne("projects",
                    Filters.and(
                            Filters.eq("tenantId", TENANT),
                            Filters.eq("name", requestedProject)));
            return p != null;
        });
        assertThat(projectAppeared)
                .as("Vance should have called project_create within 90s. "
                        + "If this fails: check brain.log for tool calls "
                        + "and consider tightening the prompt.")
                .isTrue();

        Document project = findOne("projects",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", requestedProject)));
        assertThat(project).isNotNull();
        assertThat(project.getString("kind"))
                .as("user-created project must be NORMAL")
                .isEqualTo("NORMAL");

        // ProjectCreateTool spawns a session and chat-process; both
        // should be there too.
        Document session = findOne("sessions",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("projectId", requestedProject)));
        assertThat(session)
                .as("project_create should have opened a session in '%s'", requestedProject)
                .isNotNull();
        assertThat(session.getString("userId"))
                .as("session user should be the hub user")
                .isEqualTo(USER_LOGIN);

        Document chatProcess = findOne("think_processes",
                Filters.and(
                        Filters.eq("sessionId", session.getString("sessionId")),
                        Filters.eq("name", "chat")));
        assertThat(chatProcess)
                .as("project_create should have bootstrapped a chat-process")
                .isNotNull();
        assertThat(chatProcess.getString("thinkEngine"))
                .as("regular project's chat-engine must be Arthur, not Vance")
                .isEqualTo("arthur");

        // Cross-project parent — the whole point of phase 3.6.
        assertThat(chatProcess.getString("parentProcessId"))
                .as("Arthur's parentProcessId should point back to the Vance hub-process")
                .isEqualTo(vanceProcessId);
    }

    private Document findOne(String collection, org.bson.conversions.Bson filter) {
        return mongo.getCollection(collection).find(filter).first();
    }

    private List<Document> findAll(String collection, org.bson.conversions.Bson filter) {
        List<Document> out = new ArrayList<>();
        mongo.getCollection(collection).find(filter).into(out);
        return out;
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
