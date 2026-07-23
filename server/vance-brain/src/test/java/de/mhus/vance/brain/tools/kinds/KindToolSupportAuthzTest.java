package de.mhus.vance.brain.tools.kinds;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.brain.documents.DocumentBufferService;
import de.mhus.vance.brain.documents.DocumentInvalidationEmitter;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolInvocationContext;
import org.junit.jupiter.api.Test;

/**
 * The per-document authorization gate for the LLM-tool write path
 * (permission-system F1, finding #9): every tool-driven document write
 * enforces {@code Document(target)} against the acting subject, so the
 * resolver's reserved-prefix rule (R4 → ADMIN) actually fires for
 * {@code _vance/...} targets instead of the coarse EXECUTE-on-caller-scope
 * check letting them through.
 */
class KindToolSupportAuthzTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final KindToolSupport support = new KindToolSupport(
            mock(DocumentBufferService.class), mock(DocumentService.class),
            mock(EddieContext.class), mock(DocumentInvalidationEmitter.class),
            permissionService, contextFactory);

    @Test
    void enforceDocWrite_checks_target_document_against_tool_subject() {
        SecurityContext subject = SecurityContext.user("alice", "acme", java.util.List.of());
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(subject);
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj", "sess", "proc", "alice");

        support.enforceDocWrite(ctx, "_vance", "_vance/scheduler/x.yaml", Action.CREATE);

        verify(contextFactory).forToolSubject("acme", "alice");
        verify(permissionService).enforce(
                eq(subject),
                eq(new Resource.Document("acme", "_vance", "_vance/scheduler/x.yaml")),
                eq(Action.CREATE));
    }
}
