package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A run is refused at its start when a tool it calls needs a user login the user has not made —
 * unless the state says what to do about it with {@code catch: { auth_error: … }}.
 */
class MagratheaLoginCheckTest {

    private static final String YAML_REFUSED = """
            description: posts to slack
            start: post
            states:
              post:
                type: tool_task
                tool: slack.post
                params: {}
                on:
                  success: done
              done:
                type: terminal
                outcome: success
            """;

    private static final String YAML_HANDLED = """
            description: posts to slack, asks to sign in when it cannot
            start: post
            states:
              post:
                type: tool_task
                tool: slack.post
                params: {}
                on:
                  success: done
                catch:
                  auth_error: done
              done:
                type: terminal
                outcome: success
            """;

    private final MagratheaJournalService journal = mock(MagratheaJournalService.class);
    private final MagratheaWorkflowLoader loader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService tasks = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager lanes = mock(MagratheaProjectLaneManager.class);
    private final MagratheaTaskExecutor taskExecutor = mock(MagratheaTaskExecutor.class);
    private final MagratheaTypeExecutor toolExecutor = mock(MagratheaTypeExecutor.class);

    private final MagratheaWorkflowService service = new MagratheaWorkflowService(
            loader,
            mock(de.mhus.vance.shared.document.DocumentService.class),
            journal,
            tasks,
            lanes,
            taskExecutor,
            mock(MagratheaLocalDispatch.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaStateProjector.class),
            mock(MagratheaOwnerNotifier.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
            mock(de.mhus.vance.shared.inbox.MaximegalonService.class),
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class),
            org.mockito.Mockito.mock(MagratheaRunCost.class));

    @BeforeEach
    void wire() {
        doAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                })
                .when(lanes)
                .submitTracked(any(), any());
        when(journal.append(any(), any(), any(), any(JournalRecord.class)))
                .thenReturn(MagratheaJournalEntry.builder().build());
        when(tasks.insert(any(MagratheaTaskDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        MagratheaWorkflowLoader real = new MagratheaWorkflowLoader(null);
        when(loader.load(any(), any(), eq("refused")))
                .thenAnswer(inv -> Optional.of(real.validateYaml("refused", YAML_REFUSED)));
        when(loader.load(any(), any(), eq("handled")))
                .thenAnswer(inv -> Optional.of(real.validateYaml("handled", YAML_HANDLED)));
        when(taskExecutor.executorFor(any())).thenReturn(toolExecutor);
        // Only the tool state has a problem; the terminal asks for nothing.
        when(toolExecutor.unmetLogins(any(), any(), any(), any()))
                .thenAnswer(inv -> "post"
                                .equals(inv.<de.mhus.vance.shared.magrathea.MagratheaStateSpec>getArgument(0)
                                        .name())
                        ? List.of("state 'post' calls 'slack.post', but user 'alice' has not connected 'slack'")
                        : List.of());
    }

    @Test
    void a_run_whose_tool_needs_a_login_the_user_has_not_made_is_not_started() {
        assertThatThrownBy(() -> service.start("acme", "proj", "refused", Map.of(), "alice"))
                .isInstanceOf(MagratheaWorkflowService.MagratheaWorkflowException.class)
                .hasMessageContaining("alice")
                .hasMessageContaining("slack")
                .hasMessageContaining("auth_error");

        // Nothing was written: no run exists.
        org.mockito.Mockito.verify(journal, org.mockito.Mockito.never())
                .append(any(), any(), any(), any(JournalRecord.class));
    }

    @Test
    void a_state_that_catches_auth_error_opts_out_and_the_run_starts() {
        String runId = service.start("acme", "proj", "handled", Map.of(), "alice");

        assertThat(runId).isNotBlank();
    }
}
