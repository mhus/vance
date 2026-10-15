package de.mhus.vance.shared.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExternalEmailsTest {

    @Test
    void normalize_lowercasesAndTrims() {
        assertThat(ExternalEmails.normalize("  Wile.Coyote@ACME.com ")).isEqualTo("wile.coyote@acme.com");
    }

    @Test
    void normalize_keepsDotsOutsideGmail() {
        assertThat(ExternalEmails.normalize("wile.coyote@acme.com")).isEqualTo("wile.coyote@acme.com");
    }

    @Test
    void normalize_gmailDropsDots_andMapsGooglemail() {
        assertThat(ExternalEmails.normalize("Mike.X@gmail.com")).isEqualTo("mikex@gmail.com");
        assertThat(ExternalEmails.normalize("mike.x@googlemail.com")).isEqualTo("mikex@gmail.com");
    }

    @Test
    void normalize_rejectsMalformed() {
        assertThat(ExternalEmails.normalize(null)).isNull();
        assertThat(ExternalEmails.normalize("nobody")).isNull();
        assertThat(ExternalEmails.normalize("@acme.com")).isNull();
        assertThat(ExternalEmails.normalize("a@b@acme.com")).isNull();
        assertThat(ExternalEmails.normalize("wile@")).isNull();
        assertThat(ExternalEmails.normalize("...@gmail.com")).isNull();
    }

    @Test
    void plusTag_isDetectedInLocalPartOnly() {
        assertThat(ExternalEmails.hasPlusTag("wile+x@acme.com")).isTrue();
        assertThat(ExternalEmails.hasPlusTag("wile@acme.com")).isFalse();
    }

    @Test
    void parts_andGmailCheck() {
        assertThat(ExternalEmails.localPart("wile@acme.com")).isEqualTo("wile");
        assertThat(ExternalEmails.domain("wile@acme.com")).isEqualTo("acme.com");
        assertThat(ExternalEmails.isGmail("GMAIL.com")).isTrue();
        assertThat(ExternalEmails.isGmail("googlemail.com")).isTrue();
        assertThat(ExternalEmails.isGmail("acme.com")).isFalse();
    }
}
