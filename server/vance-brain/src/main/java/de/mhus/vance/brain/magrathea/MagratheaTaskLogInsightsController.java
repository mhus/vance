package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.insights.WorkflowTaskRunInsightsDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Insights view of what workflow tasks ran in a project: which,
 * on which pod, when and for how long. Reads the shared run journal, so
 * every pod gives the same answer.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/projects/{project}/insights")
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MagratheaTaskLogInsightsController {

    static final int LIMIT = 200;

    private final MagratheaJournalService journalService;
    private final RequestAuthority authority;

    @GetMapping("/workflow-tasks")
    public List<WorkflowTaskRunInsightsDto> workflowTasks(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        return journalService.recentTaskExecutions(tenant, project, LIMIT).stream()
                .map(MagratheaTaskLogInsightsController::toDto)
                .toList();
    }

    static WorkflowTaskRunInsightsDto toDto(MagratheaJournalService.TaskExecution e) {
        return WorkflowTaskRunInsightsDto.builder()
                .workflowRunId(e.workflowRunId())
                .workflowName(e.workflowName())
                .state(e.state())
                .taskType(e.taskType().name())
                .podId(e.podId())
                .startedAt(e.startedAt())
                .finishedAt(e.finishedAt())
                .durationMs(e.durationMs())
                .outcome(e.outcome())
                .build();
    }
}
