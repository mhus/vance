package de.mhus.vance.brain.eddie.plan;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-format tests for {@link PlanFusionService}. The service has no
 * external dependencies — exercise the prefix rules and ordering
 * directly.
 */
class PlanFusionServiceTest {

    private final PlanFusionService service = new PlanFusionService();

    @Test
    void empty_eddie_emptyLinks_emptyFusion() {
        ThinkProcessDocument eddie = eddie("eddie-1");

        TodosUpdatedNotification fused = service.fuse(eddie);

        assertThat(fused.getTodos()).isEmpty();
        assertThat(fused.getProcessId()).isEqualTo("eddie-1");
        assertThat(fused.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    void eddieOnlyTodos_getEddiePrefix() {
        ThinkProcessDocument eddie = eddie("eddie-1");
        eddie.setTodos(new ArrayList<>(List.of(
                todo("1.1", TodoStatus.COMPLETED, "Recherche fertig"),
                todo("1.2", TodoStatus.IN_PROGRESS, "Optionen aufstellen"))));

        TodosUpdatedNotification fused = service.fuse(eddie);

        assertThat(fused.getTodos()).extracting(TodoItem::getId)
                .containsExactly("eddie/1.1", "eddie/1.2");
        assertThat(fused.getTodos()).extracting(TodoItem::getContent)
                .containsExactly("Recherche fertig", "Optionen aufstellen");
    }

    @Test
    void workerTodos_getSourcePrefix_eddieFirst() {
        ThinkProcessDocument eddie = eddie("eddie-1");
        eddie.setTodos(new ArrayList<>(List.of(todo("e1", TodoStatus.PENDING, "eddie step"))));
        eddie.setWorkerLinks(new ArrayList<>(List.of(
                link("w-A", "arthur", "auth-refactor",
                        List.of(todo("2.1", TodoStatus.PENDING, "Migration in 3 Schritten"))))));

        TodosUpdatedNotification fused = service.fuse(eddie);

        assertThat(fused.getTodos()).extracting(TodoItem::getId)
                .containsExactly("eddie/e1", "arthur-auth-refactor/2.1");
    }

    @Test
    void linksWithoutTodos_areSkipped() {
        ThinkProcessDocument eddie = eddie("eddie-1");
        eddie.setWorkerLinks(new ArrayList<>(List.of(
                link("w-noplan", "ford", "p", null),
                link("w-empty", "arthur", "p", List.of()),
                link("w-A", "marvin", "research",
                        List.of(todo("3.1", TodoStatus.IN_PROGRESS, "deep dive"))))));

        TodosUpdatedNotification fused = service.fuse(eddie);

        assertThat(fused.getTodos()).extracting(TodoItem::getId)
                .containsExactly("marvin-research/3.1");
    }

    @Test
    void sourceLabel_fallsBack_throughAvailableFields() {
        assertThat(PlanFusionService.sourceLabel(WorkerLinkSnapshot.builder()
                .workerProcessId("id").workerProcessName("arthur").workerProjectName("p").build()))
                .isEqualTo("arthur-p");
        assertThat(PlanFusionService.sourceLabel(WorkerLinkSnapshot.builder()
                .workerProcessId("id").workerProcessName("ford").build()))
                .isEqualTo("ford");
        assertThat(PlanFusionService.sourceLabel(WorkerLinkSnapshot.builder()
                .workerProcessId("only-id").build()))
                .isEqualTo("only-id");
    }

    @Test
    void itemFields_areCopied_throughPrefixWrap() {
        ThinkProcessDocument eddie = eddie("eddie-1");
        eddie.setTodos(new ArrayList<>(List.of(
                TodoItem.builder()
                        .id("x")
                        .status(TodoStatus.IN_PROGRESS)
                        .content("the content")
                        .activeForm("doing the content")
                        .build())));

        TodosUpdatedNotification fused = service.fuse(eddie);

        TodoItem item = fused.getTodos().get(0);
        assertThat(item.getId()).isEqualTo("eddie/x");
        assertThat(item.getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(item.getContent()).isEqualTo("the content");
        assertThat(item.getActiveForm()).isEqualTo("doing the content");
    }

    private static ThinkProcessDocument eddie(String id) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId("_user_mike")
                .sessionId("sess-1")
                .name("eddie")
                .build();
    }

    private static WorkerLinkSnapshot link(String pid, String name, String project,
                                           @org.jspecify.annotations.Nullable List<TodoItem> todos) {
        return WorkerLinkSnapshot.builder()
                .workerProcessId(pid)
                .workerProcessName(name)
                .workerProjectName(project)
                .workerTodos(todos == null ? null : new ArrayList<>(todos))
                .build();
    }

    private static TodoItem todo(String id, TodoStatus status, String content) {
        return TodoItem.builder().id(id).status(status).content(content).build();
    }
}
