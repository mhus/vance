package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecretGeneratorTest {

    private final SecretGenerator gen = new SecretGenerator();

    @Test
    void token_isUrlSafeAndDistinct() {
        String a = gen.token(18);
        String b = gen.token(18);

        assertThat(a).isNotBlank().doesNotContain("=", "+", "/");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void bcrypt_verifiesAgainstStandardEncoder() {
        String hash = gen.bcrypt("s3cret");

        // Same algorithm the live PasswordService uses — a hash minted offline
        // by the scaffolder must verify against the running Brain/Anus login.
        assertThat(new BCryptPasswordEncoder().matches("s3cret", hash)).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("wrong", hash)).isFalse();
    }
}
