package de.mhus.vance.shared.password;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyServiceTest {

    private final PasswordPolicyService policy = new PasswordPolicyService();

    @Test
    void validate_acceptsAStrongUncommonPassword() {
        assertThatCode(() -> policy.validate("Tr0ub4dor-and-3-horses"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsTooShort() {
        assertThatThrownBy(() -> policy.validate("short1"))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void validate_acceptsExactlyMinLength() {
        // 10 uncommon chars — right at the boundary.
        assertThatCode(() -> policy.validate("Zx9-Qp2-Lm"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsCommonPassword() {
        assertThatThrownBy(() -> policy.validate("password123"))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("too common");
    }

    @Test
    void validate_commonListIsCaseInsensitive() {
        assertThatThrownBy(() -> policy.validate("Password123"))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("too common");
    }

    @Test
    void validate_acceptsExactly72Bytes() {
        // 72 ASCII chars = 72 bytes — the hard cap, still allowed.
        String s = "a".repeat(71) + "Z"; // uncommon, 72 bytes
        assertThatCode(() -> policy.validate(s)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejects73Bytes() {
        String s = "a".repeat(73);
        assertThatThrownBy(() -> policy.validate(s))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void validate_countsBytesNotChars_multibyteOverCap() {
        // 37 emoji × 4 bytes = 148 bytes but only 37 code points — must be
        // rejected on the byte cap, proving we measure UTF-8 bytes not length.
        String s = "😀".repeat(37);
        assertThatThrownBy(() -> policy.validate(s))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("too long");
    }
}
