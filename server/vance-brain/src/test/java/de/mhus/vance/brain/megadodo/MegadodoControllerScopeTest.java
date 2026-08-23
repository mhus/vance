package de.mhus.vance.brain.megadodo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.megadodo.MegadodoService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The scope a request is authorized against has to be the scope it reads.
 *
 * <p>The trace endpoint takes {@code projectId} from a query parameter, checks
 * {@code Project ADMIN} on it, and then used to read tenant-wide — so a project
 * admin could name their own project, pass any trace id, and get another
 * project's feed rows. Trace ids are borrowed correlation keys (a session id, a
 * run id, and for {@code setting.change} an enumerable
 * {@code scope:scopeId:key}), so guessing one is not the obstacle.
 *
 * <p>Controller tests are opt-in in this tree; this one exists because the
 * failure is silent — too much access does not throw, it answers.
 */
class MegadodoControllerScopeTest {

    private MegadodoService service;
    private MegadodoController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = mock(MegadodoService.class);
        RequestAuthority authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        controller = new MegadodoController(service, authority);
        when(service.byTrace(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void trace_withAProject_readsOnlyThatProject() {
        controller.trace("acme", "run_42", "proj-a", request);

        verify(service).byTrace(eq("acme"), eq("proj-a"), eq("run_42"));
    }

    @Test
    void trace_withoutAProject_readsTheWholeTenant() {
        // Reaching the tenant-wide rows (user created, project created — they
        // carry no projectId) requires passing the tenant gate instead.
        controller.trace("acme", "run_42", null, request);

        verify(service).byTrace(eq("acme"), eq(null), eq("run_42"));
    }

    @Test
    void trace_withABlankProject_isTheTenantCase_notAProjectNamedEmpty() {
        controller.trace("acme", "run_42", "   ", request);

        verify(service).byTrace(eq("acme"), eq(null), eq("run_42"));
    }
}
