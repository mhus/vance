package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.script.VanceScriptApi.ScriptHostException;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptSecretApi;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScriptSecretApiTest {

    private static final ToolInvocationContext SCOPE =
            new ToolInvocationContext("t", "p", "s", "proc", "u");

    private SecretResolver resolver;
    private ScriptSecretApi api;

    @BeforeEach
    void setUp() {
        resolver = mock(SecretResolver.class);
        api = new ScriptSecretApi(resolver, SCOPE);
    }

    @AfterEach
    void tearDown() {
        VanceScriptApi.clearActiveSecretTee();
    }

    @Test
    void get_resolvesViaResolver_returnsValue() {
        when(resolver.resolve(eq("{{secret:vault:k}}"), any())).thenReturn("s3cr3t");
        assertThat(api.get("vault:k")).isEqualTo("s3cr3t");
    }

    @Test
    void get_referenceLeftUnchanged_returnsNull() {
        // resolver echoes the input (unbound / non-matching ref) → must not
        // return the literal placeholder.
        when(resolver.resolve(eq("{{secret:vault:weird key}}"), any()))
                .thenReturn("{{secret:vault:weird key}}");
        assertThat(api.get("vault:weird key")).isNull();
    }

    @Test
    void get_emptyResult_returnsNull() {
        when(resolver.resolve(any(), any())).thenReturn("");
        assertThat(api.get("vault:missing")).isNull();
    }

    @Test
    void get_blankReference_throws() {
        assertThatThrownBy(() -> api.get("  "))
                .isInstanceOf(ScriptHostException.class)
                .hasMessageContaining("reference must not be empty");
    }

    @Test
    void get_noTenantScope_throws() {
        ScriptSecretApi noTenant = new ScriptSecretApi(
                resolver, new ToolInvocationContext("", "p", "s", "proc", "u"));
        assertThatThrownBy(() -> noTenant.get("vault:k"))
                .isInstanceOf(ScriptHostException.class)
                .hasMessageContaining("tenant-scoped");
    }

    @Test
    void get_recordsResolvedValueIntoActiveTee() {
        when(resolver.resolve(any(), any())).thenReturn("s3cr3t");
        Set<String> tee = new HashSet<>();
        VanceScriptApi.setActiveSecretTee(tee);

        api.get("vault:k");

        assertThat(tee).contains("s3cr3t");
    }
}
