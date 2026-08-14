package de.mhus.vance.brain.trillian;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Covers the two authority-related contracts of the bootstrap: the minted
 * service-account gets a project-scoped grant, and the headless session is
 * owned by the account's <em>name</em> — {@code SessionDocument.userId} feeds
 * the {@code SecurityContext}, and grants are matched on the name.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianSessionBootstrapperTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "trillian-test";

    @Mock
    UserService userService;
    @Mock
    SessionService sessionService;
    @Mock
    ThinkProcessService thinkProcessService;
    @Mock
    ThinkEngineService thinkEngineService;
    @Mock
    RecipeResolver recipeResolver;
    @Mock
    LaneScheduler laneScheduler;
    @Mock
    ChatMessageService chatMessageService;
    @Mock
    PermissionBootstrap permissionBootstrap;
    @Mock
    ObjectProvider<PermissionBootstrap> permissionBootstrapProvider;
    @Mock
    ThinkEngine engine;

    TrillianSessionBootstrapper bootstrapper;

    /** Real registry with the baseline Nature — the seeding path is real logic. */
    private de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry natureRegistry() {
        return new de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry(
                java.util.List.of(
                        new de.mhus.vance.brain.trillian.nature.TrillianNature0(
                                thinkProcessService)));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bootstrapper = new TrillianSessionBootstrapper(userService, sessionService,
                thinkProcessService, thinkEngineService, recipeResolver, laneScheduler,
                chatMessageService, natureRegistry(), permissionBootstrapProvider);

        when(userService.existsByTenantAndName(anyString(), anyString())).thenReturn(false);
        when(userService.createServiceAccount(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    UserDocument user = new UserDocument();
                    user.setName(inv.getArgument(1));
                    user.setId("mongo-object-id");
                    return user;
                });
        when(recipeResolver.applyDefaulting(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(appliedRecipe());
        when(engine.name()).thenReturn("trillian-user");
        when(engine.version()).thenReturn("1");
        when(thinkEngineService.resolve("trillian-user")).thenReturn(Optional.of(engine));

        SessionDocument userSession = new SessionDocument();
        userSession.setSessionId("sess_user");
        when(sessionService.create(anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), anyBoolean())).thenReturn(userSession);

        ThinkProcessDocument userProcess = new ThinkProcessDocument();
        userProcess.setId("user-process-id");
        when(thinkProcessService.create(anyString(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(userProcess);

        doAnswer(inv -> {
            ((Callable<?>) inv.getArgument(1)).call();
            return CompletableFuture.completedFuture(null);
        }).when(laneScheduler).submit(anyString(), any(Callable.class));

        doAnswer(inv -> {
            ((Consumer<PermissionBootstrap>) inv.getArgument(0)).accept(permissionBootstrap);
            return null;
        }).when(permissionBootstrapProvider).ifAvailable(any());
    }

    @Test
    void bootstrap_grantsTheMintedAccountAdminOnTheControlProject() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess());

        ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
        verify(permissionBootstrap).grantProjectAdmin(eq(TENANT), eq(PROJECT), username.capture());
        org.assertj.core.api.Assertions.assertThat(username.getValue())
                .startsWith("_trillian-0");
    }

    @Test
    void bootstrap_scopesTheGrantToOneProject_neverTheWholeTenant() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess());

        // Cross-project spawning stays denied until someone grants it
        // explicitly — an ephemeral, LLM-driven account must not hold
        // tenant-wide authority.
        verify(permissionBootstrap, never()).grantTenantAdmin(any(), any());
    }

    @Test
    void bootstrap_ownsTheUserSessionByAccountName_notMongoId() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess());

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(sessionService).create(eq(TENANT), owner.capture(), eq(PROJECT), any(),
                anyString(), anyString(), any(), eq(true));
        org.assertj.core.api.Assertions.assertThat(owner.getValue())
                .startsWith("_trillian-0")
                .isNotEqualTo("mongo-object-id");
    }

    @Test
    void bootstrap_grantsTheSameNameItPutsOnTheSession() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess());

        ArgumentCaptor<String> granted = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(permissionBootstrap).grantProjectAdmin(any(), any(), granted.capture());
        verify(sessionService).create(any(), owner.capture(), any(), any(), any(), any(),
                any(), anyBoolean());
        // A mismatch here is invisible at runtime until the first tool call
        // is denied — exactly the failure this pair of fixes removes.
        org.assertj.core.api.Assertions.assertThat(granted.getValue())
                .isEqualTo(owner.getValue());
    }

    @Test
    void bootstrap_announcesTheWorkerIdentityAsAPersistentChatMessage() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess());

        ArgumentCaptor<ChatMessageDocument> message =
                ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService).append(message.capture());
        ArgumentCaptor<String> granted = ArgumentCaptor.forClass(String.class);
        verify(permissionBootstrap).grantProjectAdmin(any(), any(), granted.capture());

        // The operator needs this name to widen the worker's reach later;
        // the brain log is not a place they can be expected to look.
        org.assertj.core.api.Assertions.assertThat(message.getValue().getContent())
                .contains(granted.getValue())
                .contains(PROJECT);
        org.assertj.core.api.Assertions.assertThat(message.getValue().getSessionId())
                .isEqualTo("sess_control");
    }

    @Test
    void announceFailure_doesNotAbortTheBootstrap() {
        org.mockito.Mockito.when(chatMessageService.append(any()))
                .thenThrow(new IllegalStateException("mongo down"));

        bootstrapper.maybeBootstrap(controlSession(), controlProcess());

        // The pair is wired regardless — losing the announcement must not
        // cost the session its worker.
        verify(thinkProcessService).replaceEngineParams(eq("control-process-id"), any());
    }

    @Test
    void theAnnouncement_usesTheNaturesCallName() {
        de.mhus.vance.brain.trillian.nature.TrillianNature named =
                new de.mhus.vance.brain.trillian.nature.TrillianNature() {
                    @Override
                    public String id() {
                        return "adam";
                    }

                    @Override
                    public String title() {
                        return "test";
                    }

                    @Override
                    public Map<String, Object> initialAttributes(
                            String tenantId, String projectId, String account) {
                        return Map.of("name", "Ada");
                    }

                    @Override
                    public String callName(Map<String, Object> attributes) {
                        return "Ada";
                    }
                };
        bootstrapper = new TrillianSessionBootstrapper(userService, sessionService,
                thinkProcessService, thinkEngineService, recipeResolver, laneScheduler,
                chatMessageService,
                new de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry(
                        java.util.List.of(named)),
                permissionBootstrapProvider);

        bootstrapper.maybeBootstrap(controlSession(), controlProcess("adam"));

        ArgumentCaptor<de.mhus.vance.shared.chat.ChatMessageDocument> message =
                ArgumentCaptor.forClass(de.mhus.vance.shared.chat.ChatMessageDocument.class);
        verify(chatMessageService).append(message.capture());
        // The human meets a name, not a class — and the account is still
        // there, because that is what they need in order to grant access.
        org.assertj.core.api.Assertions.assertThat(message.getValue().getContent())
                .startsWith("Ada is ready")
                .contains("_trillian-adam-");
    }

    @Test
    void aPodlessProject_getsNoTrillian() {
        // _user_* and system projects have no home pod — they follow
        // whichever pod took the WebSocket. A Trillian there would never
        // be woken by the heartbeat, which scans by home node.
        SessionDocument session = controlSession();
        session.setProjectId("_user_marvin");

        bootstrapper.maybeBootstrap(session, controlProcess());

        verify(userService, never()).createServiceAccount(
                anyString(), anyString(), any(), any(), any());
        verify(sessionService, never()).create(anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void workerRecipe_isDerivedFromTheNature() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess("a"));

        // The loop's prompt reads this instead of naming a recipe in
        // prose, so a new Nature brings its own worker without forking
        // the prompt.
        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();
        verify(thinkProcessService).create(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), params.capture(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(params.getValue())
                .containsEntry(TrillianSessionBootstrapper.PARAM_WORKER_RECIPE,
                        "trillian-worker-a");
    }

    @Test
    void workerRecipe_defaultsToNatureZero() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess());

        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();
        verify(thinkProcessService).create(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), params.capture(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(params.getValue())
                .containsEntry(TrillianSessionBootstrapper.PARAM_WORKER_RECIPE,
                        "trillian-worker-0");
    }

    @Test
    void theAccountName_carriesTheNatureAsItsOwnPart() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess("alpha"));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(userService).createServiceAccount(
                anyString(), name.capture(), any(), any(), any());

        // Three parts, so a Nature id may be a word rather than a letter,
        // and so the id can be read back out of the name at all — with
        // _trillian-a1535 that required knowing where the id ends.
        org.assertj.core.api.Assertions.assertThat(name.getValue())
                .matches("_trillian-alpha-\\d{4}");
    }

    @Test
    void theAccountName_staysAServiceAccount() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess("alpha"));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(userService).createServiceAccount(
                anyString(), name.capture(), any(), any(), any());

        // The leading underscore is the tenant-wide marker for accounts
        // nobody logs into; losing it would make the worker look like a
        // person in every user list.
        org.assertj.core.api.Assertions.assertThat(name.getValue()).startsWith("_");
    }

    @Test
    void theTitle_isSeededButNotTheIdentity() {
        bootstrapper.maybeBootstrap(controlSession(), controlProcess("alpha"));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(userService).createServiceAccount(
                anyString(), name.capture(), any(), title.capture(), any());

        // The title is what a human reads and may rename; the account
        // name never changes. Seeding one from the other is a starting
        // value, not a derivation to be recomputed later.
        org.assertj.core.api.Assertions.assertThat(title.getValue())
                .isEqualTo("Trillian " + name.getValue().substring("_trillian-".length()));
    }

    @Test
    void aPersistentNature_seedsTheWorkerFromItsOwnStore() {
        // The carrying dance across an archive only covers a reactivate.
        // A Nature that keeps its attributes somewhere durable has to be
        // asked when there is nothing to carry — otherwise "persistent"
        // means "persistent until the next restart".
        de.mhus.vance.brain.trillian.nature.TrillianNature persistent =
                new de.mhus.vance.brain.trillian.nature.TrillianNature() {
                    @Override
                    public String id() {
                        return "adam";
                    }

                    @Override
                    public String title() {
                        return "test";
                    }

                    @Override
                    public Map<String, Object> initialAttributes(
                            String tenantId, String projectId, String account) {
                        return Map.of("persona", "restored from disk");
                    }
                };
        bootstrapper = new TrillianSessionBootstrapper(userService, sessionService,
                thinkProcessService, thinkEngineService, recipeResolver, laneScheduler,
                chatMessageService,
                new de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry(
                        java.util.List.of(persistent)),
                permissionBootstrapProvider);

        bootstrapper.maybeBootstrap(controlSession(), controlProcess("adam"));

        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();
        verify(thinkProcessService).create(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), params.capture(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(params.getValue())
                .containsEntry(TrillianInternalApi.PARAM_ATTRIBUTES,
                        Map.of("persona", "restored from disk"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> paramsCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @Test
    void nonControlProcess_isNotBootstrapped() {
        ThinkProcessDocument arthur = new ThinkProcessDocument();
        arthur.setId("chat");
        arthur.setThinkEngine("arthur");

        bootstrapper.maybeBootstrap(controlSession(), arthur);

        verify(permissionBootstrap, never()).grantProjectAdmin(any(), any(), any());
        verify(userService, never()).createServiceAccount(any(), any(), any(), any(), any());
    }

    private static SessionDocument controlSession() {
        SessionDocument session = new SessionDocument();
        session.setSessionId("sess_control");
        session.setTenantId(TENANT);
        session.setProjectId(PROJECT);
        session.setUserId("marvin.acme");
        return session;
    }

    private static ThinkProcessDocument controlProcess() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("control-process-id");
        process.setThinkEngine(TrillianSessionBootstrapper.CONTROL_ENGINE_NAME);
        return process;
    }

    /** Control process pinning a specific Nature. */
    private static ThinkProcessDocument controlProcess(String nature) {
        ThinkProcessDocument process = controlProcess();
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_NATURE, nature);
        process.setEngineParams(params);
        return process;
    }

    private static AppliedRecipe appliedRecipe() {
        return new AppliedRecipe(
                "trillian-user-0", "trillian-user", Map.of(), null, null,
                PromptMode.APPEND, null, null, null, List.of(), null,
                RecipeSource.RESOURCE, List.of(), null);
    }
}
