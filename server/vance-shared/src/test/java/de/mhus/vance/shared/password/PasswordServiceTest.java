package de.mhus.vance.shared.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordServiceTest {

    private final PasswordService svc = new PasswordService();

    @Test
    void hash_doesNotReturnPlaintext() {
        String hash = svc.hash("hunter2");

        assertThat(hash)
                .isNotNull()
                .isNotEqualTo("hunter2")
                // BCrypt prefix.
                .startsWith("$2");
    }

    @Test
    void hash_isSalted_soSamePlaintextProducesDifferentHashes() {
        String h1 = svc.hash("hunter2");
        String h2 = svc.hash("hunter2");

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void verify_acceptsCorrectPassword() {
        String hash = svc.hash("hunter2");

        assertThat(svc.verify("hunter2", hash)).isTrue();
    }

    @Test
    void verify_rejectsWrongPassword() {
        String hash = svc.hash("hunter2");

        assertThat(svc.verify("hunter3", hash)).isFalse();
        assertThat(svc.verify("Hunter2", hash)).isFalse();
        assertThat(svc.verify("", hash)).isFalse();
    }

    @Test
    void verify_acceptsBothHashesOfSamePlaintext() {
        // Salting check: two distinct hashes of the same plaintext must
        // both verify.
        String h1 = svc.hash("hunter2");
        String h2 = svc.hash("hunter2");

        assertThat(svc.verify("hunter2", h1)).isTrue();
        assertThat(svc.verify("hunter2", h2)).isTrue();
    }

    @Test
    void verify_returnsFalse_onMalformedHash() {
        // BCrypt encoder treats unparseable hashes as no-match, not as
        // exception — important so a corrupted DB row doesn't throw a 500.
        assertThat(svc.verify("hunter2", "not-a-bcrypt-hash")).isFalse();
        assertThat(svc.verify("hunter2", "")).isFalse();
    }
}
