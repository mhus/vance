package de.mhus.vance.brain.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RunControllerTest {

    private final RunSourceRegistry registry = mock(RunSourceRegistry.class);
    private final RequestAuthority authority = mock(RequestAuthority.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final RunController controller = new RunController(registry, authority);

    @Test
    void listChecksProjectReadBeforeAnsweringAnything() {
        when(registry.list(any(), any(), any(), anyInt())).thenReturn(List.of());

        controller.list("acme", "proj", null, request);

        verify(authority).enforce(request, new Resource.Project("acme", "proj"), Action.READ);
    }

    @Test
    void anUnknownRunIs404NotAnEmptyBody() {
        when(registry.get(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get("acme", "workflow:ghost", "proj", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRunOfAnotherProjectIs404AndNot403() {
        // The sources report a foreign run as absent, so the controller
        // cannot distinguish it from a nonexistent one — which is the
        // point: a 403 here would confirm that the run exists.
        when(registry.get(any(), eq("acme"), eq("proj"), eq("workflow:elsewhere"))).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                controller.get("acme", "workflow:elsewhere", "proj", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void limitIsClampedRatherThanTrusted() {
        when(registry.list(any(), any(), any(), anyInt())).thenReturn(List.of());

        controller.list("acme", "proj", 100000, request);
        verify(registry).list(any(), eq("acme"), eq("proj"), eq(200));

        controller.list("acme", "proj", -5, request);
        verify(registry).list(any(), eq("acme"), eq("proj"), eq(1));
    }

    @Test
    void detailComesBackWhenTheRegistryHasIt() {
        RunDetailDto detail = RunDetailDto.builder()
                .summary(RunSummaryDto.builder()
                        .runId("workflow:r1").source("workflow").name("demo")
                        .status(RunStatus.DONE).projectId("proj").build())
                .build();
        when(registry.get(any(), eq("acme"), eq("proj"), eq("workflow:r1"))).thenReturn(Optional.of(detail));

        assertThat(controller.get("acme", "workflow:r1", "proj", request))
                .isSameAs(detail);
    }
}
