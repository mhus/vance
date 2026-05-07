package de.mhus.vance.foot.tools.pack;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.ToolInvocationContext;
import org.junit.jupiter.api.Test;

class EnvSecretResolverTest {

    private final EnvSecretResolver resolver = new EnvSecretResolver();
    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("", null, null, null, null);

    @Test
    void plainStringWithoutTemplate_passesThrough() {
        assertThat(resolver.resolve("plain value", CTX)).isEqualTo("plain value");
    }

    @Test
    void unknownEnvVar_substitutesEmptyString() {
        // Use a deliberately rare name so the assertion is stable.
        assertThat(resolver.resolve("token=${env:VANCE_FOOT_DOES_NOT_EXIST_XYZ123}", CTX))
                .isEqualTo("token=");
    }

    @Test
    void knownEnvVar_substitutesValue() {
        // PATH is essentially always set on a unix system; we just check
        // the substitution mechanism, not the value.
        String result = resolver.resolve("PATH=${env:PATH}", CTX);
        assertThat(result).startsWith("PATH=").isNotEqualTo("PATH=");
    }

    @Test
    void multipleReferences_areAllReplaced() {
        String result = resolver.resolve(
                "user=${env:USER_ABSENT_X}, home=${env:HOME_ABSENT_Y}", CTX);
        assertThat(result).isEqualTo("user=, home=");
    }

    @Test
    void nullInput_returnsNull() {
        assertThat(resolver.resolve(null, CTX)).isNull();
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertThat(resolver.resolve("", CTX)).isEmpty();
    }

    @Test
    void wrongPattern_passesThrough() {
        // Server-side {{secret:foo}} is NOT recognised on foot — passes through literally.
        assertThat(resolver.resolve("{{secret:foo}}", CTX)).isEqualTo("{{secret:foo}}");
        // Mismatched braces also pass through.
        assertThat(resolver.resolve("${env:FOO", CTX)).isEqualTo("${env:FOO");
    }
}
