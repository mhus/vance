package de.mhus.vance.brain.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.vault.VaultException;
import de.mhus.vance.shared.vault.VaultScope;
import de.mhus.vance.shared.vault.VaultService;
import de.mhus.vance.shared.vault.VaultService.SecretFormat;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VaultSecretGenerateToolTest {

    private static final VaultScope SCOPE = new VaultScope("t", "u", "p");
    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("t", "p", "s", "proc", "u");

    private VaultService vaultService;
    private VaultToolSupport support;
    private VaultSecretGenerateTool tool;

    @BeforeEach
    void setUp() {
        vaultService = mock(VaultService.class);
        support = mock(VaultToolSupport.class);
        when(support.enforceAndScope(any())).thenReturn(SCOPE);
        tool = new VaultSecretGenerateTool(vaultService, support);
    }

    @Test
    void invoke_generatesWithDefaults_andReturnsRefNeverValue() {
        Map<String, Object> out = tool.invoke(Map.of("key", "db-pw"), CTX);

        verify(vaultService).generateSecret(eq(SCOPE), eq("db-pw"), eq(SecretFormat.ALPHANUMERIC), eq(32));
        assertThat(out).containsEntry("key", "db-pw")
                .containsEntry("ref", "vault:db-pw")
                .containsEntry("generated", true)
                .containsEntry("format", "alphanumeric");
        // The generated value is never surfaced.
        assertThat(out).doesNotContainKey("value");
    }

    @Test
    void invoke_honoursFormatAndLength() {
        tool.invoke(Map.of("key", "k", "format", "hex", "length", 16), CTX);
        verify(vaultService).generateSecret(SCOPE, "k", SecretFormat.HEX, 16);
    }

    @Test
    void invoke_missingKey_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("key");
    }

    @Test
    void invoke_invalidFormat_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of("key", "k", "format", "rot13"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("format");
    }

    @Test
    void invoke_lengthOutOfRange_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of("key", "k", "length", 4), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("length");
    }

    @Test
    void invoke_vaultException_becomesToolException() {
        doThrow(new VaultException("no vault bound"))
                .when(vaultService).generateSecret(any(), any(), any(), anyInt());

        assertThatThrownBy(() -> tool.invoke(Map.of("key", "k"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no vault bound");
    }
}
