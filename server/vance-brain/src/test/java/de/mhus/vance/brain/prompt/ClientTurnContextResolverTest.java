package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.BoundDocSelection;
import de.mhus.vance.brain.applications.ActiveAppPromptResolver;
import de.mhus.vance.brain.chat.CollabContextResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.tools.client.CortexBoundDocumentResolver;
import de.mhus.vance.brain.tools.client.CortexPromptResolver;
import de.mhus.vance.brain.tools.client.CortexTurnSelectionHolder;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-turn client context an engine must not lose.
 *
 * <p>Regression for 2026-08-25: a chat running on Frankie (recipe
 * {@code app-builder}) never saw the {@code activeApp} hint the client
 * had sent, because only Arthur and Eddie carried the extraction code.
 * The agent picked a plausible-looking app folder from the project and
 * rewrote three documents in it while the user watched a different one.
 * The signals are now extracted here, once, and the tests that matter
 * are about what survives the extraction — not about any one engine.
 */
class ClientTurnContextResolverTest {

    private static final String PROC = "proc-1";

    private ActiveAppPromptResolver appPrompts;
    private CortexBoundDocumentResolver boundDocs;
    private CortexTurnSelectionHolder selections;
    private ClientTurnContextResolver resolver;

    @BeforeEach
    void setUp() {
        appPrompts = mock(ActiveAppPromptResolver.class);
        boundDocs = mock(CortexBoundDocumentResolver.class);
        selections = new CortexTurnSelectionHolder();
        CortexPromptResolver cortex = mock(CortexPromptResolver.class);
        when(cortex.resolve(any())).thenReturn(new CortexPromptResolver.CortexContext(false));
        CollabContextResolver collab = mock(CollabContextResolver.class);
        when(collab.resolve(any(), any()))
                .thenReturn(CollabContextResolver.CollabContext.INACTIVE);
        resolver = new ClientTurnContextResolver(
                appPrompts, cortex, boundDocs, selections, collab);
    }

    @Test
    void activeApp_reachesThePromptContext() {
        when(appPrompts.resolve(any(), any())).thenReturn("You are in a Bistromath app.\n");

        ClientTurnContextResolver.ClientTurnContext ctx =
                resolver.resolve(process(), List.of(input(app("apps/bistromath1"))));

        Map<String, Object> vars = ctx.applyTo(PromptContextBuilder.create()).build();
        assertThat(vars.get("activeApp"))
                .isEqualTo(Map.of("folder", "apps/bistromath1", "app", "custom"));
        assertThat(vars.get("appInstructions")).isEqualTo("You are in a Bistromath app.\n");
    }

    @Test
    void lastUserInputWins_soTheNewestFolderIsTheTarget() {
        // Two messages typed while the lane was busy: the reader has
        // moved on, and the older folder must not win.
        when(appPrompts.resolve(any(), any())).thenReturn("instructions");

        ClientTurnContextResolver.ClientTurnContext ctx = resolver.resolve(process(),
                List.of(input(app("apps/first")), input(app("apps/second"))));

        assertThat(ctx.activeApp()).isNotNull();
        assertThat(ctx.activeApp().getFolder()).isEqualTo("apps/second");
    }

    @Test
    void appWithoutInstructions_dropsTheHintEntirely() {
        // An unknown app type or a throwing promptInject leaves the
        // template with a header and no body — worse than no block.
        when(appPrompts.resolve(any(), any())).thenReturn(null);

        ClientTurnContextResolver.ClientTurnContext ctx =
                resolver.resolve(process(), List.of(input(app("apps/x"))));

        assertThat(ctx.activeApp()).isNull();
        assertThat(ctx.applyTo(PromptContextBuilder.create()).build())
                .doesNotContainKey("activeApp");
    }

    @Test
    void batchWithoutUserInput_claimsNothing() {
        // An autonomous wake or a tool result: nobody is looking at
        // anything, and carrying the previous turn's folder forward would
        // point the agent at a place the reader may have left.
        ClientTurnContextResolver.ClientTurnContext ctx = resolver.resolve(process(),
                List.of(new SteerMessage.ExternalCommand(Instant.now(), null, "ping", null)));

        assertThat(ctx.activeApp()).isNull();
        assertThat(ctx.boundDocPath()).isNull();
        assertThat(ctx.voiceMode()).isFalse();
    }

    @Test
    void boundDocumentAndSelection_reachContextAndSelectionHolder() {
        when(boundDocs.resolvePath(any(), any(), any())).thenReturn("apps/bistromath1/main.js");

        ClientTurnContextResolver.ClientTurnContext ctx = resolver.resolve(process(),
                List.of(input(null, "doc-7", new BoundDocSelection(10, 20))));

        assertThat(ctx.applyTo(PromptContextBuilder.create()).build())
                .containsEntry("cortexBoundDocPath", "apps/bistromath1/main.js")
                .containsEntry("cortexBoundDocSelection", "10:20");
        assertThat(selections.get(PROC))
                .isEqualTo(new CortexTurnSelectionHolder.Selection("doc-7", 10, 20));
    }

    @Test
    void turnWithoutSelection_clearsTheStaleOne() {
        // The stash outlives the turn that filled it, so a later turn
        // with no selection has to clear it — otherwise the no-arg
        // doc_get_selection() answers with a range nobody highlighted.
        selections.set(PROC, new CortexTurnSelectionHolder.Selection("doc-old", 1, 2));

        resolver.resolve(process(), List.of(input(null, "doc-7", null)));

        assertThat(selections.get(PROC)).isNull();
    }

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(PROC);
        p.setTenantId("acme");
        p.setProjectId("test1");
        p.setSessionId("sess-1");
        return p;
    }

    private static ActiveAppContext app(String folder) {
        return ActiveAppContext.builder().folder(folder).app("custom").build();
    }

    private static SteerMessage.UserChatInput input(ActiveAppContext app) {
        return input(app, null, null);
    }

    private static SteerMessage.UserChatInput input(
            ActiveAppContext app, String boundDocumentId, BoundDocSelection selection) {
        return new SteerMessage.UserChatInput(
                Instant.parse("2026-08-25T11:58:13Z"), null, "road.runner", "Road Runner",
                "fix it", List.of(), false, app, boundDocumentId, selection);
    }
}
