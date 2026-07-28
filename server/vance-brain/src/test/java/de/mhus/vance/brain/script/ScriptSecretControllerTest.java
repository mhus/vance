package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.vault.ScriptSecretAccumulator;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.core.SecretResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ScriptSecretControllerTest {

    private static final String RUN = "run-1";

    private SecretResolver resolver;
    private RequestAuthority authority;
    private ScriptSecretController controller;
    private HttpServletRequest request;

    private static VanceJwtClaims scriptRunClaims() {
        return VanceJwtClaims.scriptRun(
                "wile.coyote", "acme", Instant.now(), Instant.now().plusSeconds(3600),
                RUN, "instant-hole", "s-1");
    }

    @BeforeEach
    void setUp() {
        resolver = mock(SecretResolver.class);
        authority = mock(RequestAuthority.class);
        controller = new ScriptSecretController(resolver, authority);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_CLAIMS)).thenReturn(scriptRunClaims());
    }

    @AfterEach
    void clean() {
        ScriptSecretAccumulator.evict(RUN);
    }

    @Test
    void getSecret_resolves_returnsValue_recordsForMasking_andEnforcesRead() {
        when(resolver.resolve(eq("{{secret:vault:k}}"), any())).thenReturn("s3cr3t");

        Map<String, Object> out = controller.getSecret("acme", "vault:k", request);

        assertThat(out).containsEntry("value", "s3cr3t");
        assertThat(ScriptSecretAccumulator.peek(RUN)).contains("s3cr3t");
        verify(authority).enforce(eq(request), any(Resource.Project.class), eq(Action.READ));
    }

    @Test
    void getSecret_unresolved_returnsNullValue_andRecordsNothing() {
        when(resolver.resolve(any(), any())).thenReturn("");

        Map<String, Object> out = controller.getSecret("acme", "vault:missing", request);

        assertThat(out.get("value")).isNull();
        assertThat(ScriptSecretAccumulator.peek(RUN)).isEmpty();
    }

    @Test
    void getSecret_blankRef_isBadRequest() {
        assertThatThrownBy(() -> controller.getSecret("acme", "  ", request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getSecret_withoutScriptRunClaims_isForbidden() {
        when(request.getAttribute(AccessFilterBase.ATTR_CLAIMS)).thenReturn(null);

        assertThatThrownBy(() -> controller.getSecret("acme", "vault:k", request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
