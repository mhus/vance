package de.mhus.vance.brain.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.vault.VaultException;
import de.mhus.vance.shared.vault.VaultScope;
import de.mhus.vance.shared.vault.VaultService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VaultSecretSetToolTest {

    private static final VaultScope SCOPE = new VaultScope("t", "u", "p");
    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("t", "p", "s", "proc", "u");

    private VaultService vaultService;
    private VaultToolSupport support;
    private VaultSecretSetTool tool;

    @BeforeEach
    void setUp() {
        vaultService = mock(VaultService.class);
        support = mock(VaultToolSupport.class);
        when(support.enforceAndScope(any())).thenReturn(SCOPE);
        tool = new VaultSecretSetTool(vaultService, support);
    }

    @Test
    void invoke_writesValue_andReturnsRefWithoutEchoingValue() {
        Map<String, Object> out = tool.invoke(Map.of("key", "api-key", "value", "s3cr3t-value"), CTX);

        verify(vaultService).writeSecret(SCOPE, "api-key", "s3cr3t-value");
        assertThat(out).containsEntry("key", "api-key")
                .containsEntry("ref", "vault:api-key")
                .containsEntry("written", true);
        assertThat(out).doesNotContainValue("s3cr3t-value");
    }

    @Test
    void invoke_missingValue_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of("key", "k"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("value");
    }

    @Test
    void invoke_vaultException_becomesToolException() {
        doThrow(new VaultException("read-only identity"))
                .when(vaultService).writeSecret(eq(SCOPE), any(), any());

        assertThatThrownBy(() -> tool.invoke(Map.of("key", "k", "value", "v"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("read-only identity");
    }
}
