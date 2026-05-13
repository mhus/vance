package de.mhus.vance.brain.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.api.hooks.HookSource;
import de.mhus.vance.api.hooks.HookType;
import de.mhus.vance.shared.inbox.InboxItemService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end runner tests on a real GraalJS context. No Spring boot —
 * we instantiate the Engine, runner, and host-API directly so the
 * test stays fast and the surface stays observable.
 */
class JsHookRunnerTest {

    private static Engine engine;
    private static JsHookRunner runner;

    @BeforeAll
    static void setup() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        runner = new JsHookRunner(engine);
        // @Value field — set the statementLimit reflectively to match
        // production default; spring would do this via the bean wiring.
        try {
            var f = JsHookRunner.class.getDeclaredField("statementLimit");
            f.setAccessible(true);
            f.set(runner, 200_000L);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void run_canReadEventPayload() {
        HookContext ctx = ctx("greet");
        HookHostApi host = host(ctx, Map.of("process", Map.of("name", "alpha")));
        HookDef def = jsDef("greet", "log.info('process=' + event.process.name);");
        HookRunResult result = runner.run(def, ctx, host.event, host);
        assertThat(result.outcome()).isEqualTo(HookRunResult.Outcome.COMPLETED);
    }

    @Test
    void run_timesOutOnInfiniteLoop() {
        HookContext ctx = ctx("loop");
        HookHostApi host = host(ctx, Map.of());
        HookDef def = jsDefWithTimeout(
                "loop",
                "while (true) {}",
                Duration.ofMillis(200));
        HookRunResult result = runner.run(def, ctx, host.event, host);
        assertThat(result.outcome()).isEqualTo(HookRunResult.Outcome.FAILED);
        assertThat(result.errorPhase()).isIn("timeout", "resourceExhausted", "cancelled");
    }

    @Test
    void run_rejectsJavaTypeAccess() {
        HookContext ctx = ctx("evil");
        HookHostApi host = host(ctx, Map.of());
        HookDef def = jsDef(
                "evil",
                "var Sys = Java.type('java.lang.System'); Sys.exit(0);");
        HookRunResult result = runner.run(def, ctx, host.event, host);
        assertThat(result.outcome()).isEqualTo(HookRunResult.Outcome.FAILED);
        assertThat(result.errorPhase()).isEqualTo("guestException");
    }

    @Test
    void run_doesNotShareGlobalsAcrossRuns() {
        HookContext ctx = ctx("mut");
        HookHostApi host = host(ctx, Map.of());

        runner.run(jsDef("mut", "globalThis.leak = 42;"), ctx, host.event, host);

        // Second run on a fresh context must not see the previous global.
        HookDef peek = jsDef(
                "peek",
                "if (typeof globalThis.leak !== 'undefined') throw new Error('leaked: ' + globalThis.leak);");
        HookRunResult result = runner.run(peek, ctx, host.event, host);
        assertThat(result.outcome()).isEqualTo(HookRunResult.Outcome.COMPLETED);
    }

    // ───── helpers ─────

    private static HookContext ctx(String name) {
        return new HookContext(
                "acme", "p1", HookEventName.PROCESS_COMPLETED, name,
                "corr-" + name, Instant.now());
    }

    private static HookHostApi host(
            HookContext ctx, Map<String, Object> eventPayload) {
        HookSettingsView settings = mock(HookSettingsView.class);
        HookHttpClient http = new HookHttpClient(
                HttpClient.newHttpClient(), Duration.ofSeconds(2),
                /*allowPrivate*/ true, Set.of(), ctx.hookName());
        InboxItemService inboxSvc = mock(InboxItemService.class);
        HookInboxClient inbox = new HookInboxClient(
                inboxSvc, ctx.tenantId(), ctx.hookName(), "user1", null);
        HookLog log = new HookLog(ctx);
        Map<String, Object> payload = new LinkedHashMap<>(eventPayload);
        return new HookHostApi(ctx, payload, http, inbox, log, settings);
    }

    private static HookDef jsDef(String name, String script) {
        return jsDefWithTimeout(name, script, Duration.ofSeconds(3));
    }

    private static HookDef jsDefWithTimeout(String name, String script, Duration timeout) {
        return new HookDef(
                name, HookEventName.PROCESS_COMPLETED,
                HookSource.PROJECT, HookType.JS, true,
                /*description*/ null, timeout, null,
                /*yaml*/ "type: js\nscript: " + script + "\n",
                /*createdByUserId*/ null,
                script,
                null, null, null);
    }
}
