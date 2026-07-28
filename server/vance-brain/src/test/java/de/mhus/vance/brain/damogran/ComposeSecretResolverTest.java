package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComposeSecretResolverTest {

    private SecretResolver secretResolver;
    private ComposeSecretResolver resolver;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        resolver = new ComposeSecretResolver(secretResolver);
    }

    private static DamogranContext ctx(@Nullable SecurityContext caller) {
        return new DamogranContext("t", "p", "proc1", "ws", "ws", null,
                "WORK", null, null, null, null, null, null, null, caller);
    }

    private static Map<String, String> secrets(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void resolve_wrapsEachRefAndReturnsEnvNameToValue() {
        when(secretResolver.resolve(eq("{{secret:vault:jira}}"), any())).thenReturn("TOK");
        when(secretResolver.resolve(eq("{{secret:project:db}}"), any())).thenReturn("PW");

        Map<String, String> out = resolver.resolve(
                secrets("JIRA", "vault:jira", "DB", "project:db"), ctx(null));

        assertThat(out).containsEntry("JIRA", "TOK").containsEntry("DB", "PW");
    }

    @Test
    void resolve_dropsReferencesThatResolveEmpty() {
        when(secretResolver.resolve(eq("{{secret:vault:missing}}"), any())).thenReturn("");
        when(secretResolver.resolve(eq("{{secret:vault:ok}}"), any())).thenReturn("V");

        Map<String, String> out = resolver.resolve(
                secrets("A", "vault:missing", "B", "vault:ok"), ctx(null));

        assertThat(out).containsOnlyKeys("B");
    }

    @Test
    void resolve_derivesUserIdFromUserCaller() {
        when(secretResolver.resolve(any(), any())).thenReturn("X");
        ArgumentCaptor<ToolInvocationContext> cap = ArgumentCaptor.forClass(ToolInvocationContext.class);

        resolver.resolve(secrets("K", "user:k"),
                ctx(SecurityContext.user("alice", "t", List.of())));

        verify(secretResolver).resolve(eq("{{secret:user:k}}"), cap.capture());
        assertThat(cap.getValue().userId()).isEqualTo("alice");
        assertThat(cap.getValue().tenantId()).isEqualTo("t");
        assertThat(cap.getValue().projectId()).isEqualTo("p");
    }

    @Test
    void resolve_systemCallerHasNoUserId() {
        when(secretResolver.resolve(any(), any())).thenReturn("X");
        ArgumentCaptor<ToolInvocationContext> cap = ArgumentCaptor.forClass(ToolInvocationContext.class);

        resolver.resolve(secrets("K", "vault:k"), ctx(SecurityContext.SYSTEM));

        verify(secretResolver).resolve(any(), cap.capture());
        assertThat(cap.getValue().userId()).isNull();
    }

    @Test
    void resolve_emptySecrets_returnsEmptyWithoutTouchingResolver() {
        assertThat(resolver.resolve(Map.of(), ctx(null))).isEmpty();
        verifyNoInteractions(secretResolver);
    }
}
