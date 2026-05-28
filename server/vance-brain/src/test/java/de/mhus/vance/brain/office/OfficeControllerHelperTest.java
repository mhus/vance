package de.mhus.vance.brain.office;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-helper tests for static parts of {@link OfficeController}.
 * The HTTP surface needs the Spring context + DocumentService +
 * settings store and lives in opt-in integration tests; here we
 * verify only the parser helpers used in the callback path.
 */
class OfficeControllerHelperTest {

    @Test
    void numericStatus_acceptsInteger() {
        assertThat(OfficeController.numericStatus(2)).isEqualTo(2);
        assertThat(OfficeController.numericStatus(6L)).isEqualTo(6);
    }

    @Test
    void numericStatus_acceptsNumericString() {
        assertThat(OfficeController.numericStatus("2")).isEqualTo(2);
        assertThat(OfficeController.numericStatus(" 6 ")).isEqualTo(6);
    }

    @Test
    void numericStatus_invalidYields_minus1() {
        assertThat(OfficeController.numericStatus(null)).isEqualTo(-1);
        assertThat(OfficeController.numericStatus("xyz")).isEqualTo(-1);
        assertThat(OfficeController.numericStatus(new Object())).isEqualTo(-1);
    }

    @Test
    void asString_passesThroughOrReturnsNull() {
        assertThat(OfficeController.asString(null)).isNull();
        assertThat(OfficeController.asString("hello")).isEqualTo("hello");
        assertThat(OfficeController.asString(42)).isEqualTo("42");
    }

    @Test
    void stripTrailingSlash_dropsTrailing() {
        assertThat(OfficeController.stripTrailingSlash("http://x/"))
                .isEqualTo("http://x");
        assertThat(OfficeController.stripTrailingSlash("http://x///"))
                .isEqualTo("http://x");
    }

    @Test
    void stripTrailingSlash_leavesCleanUntouched() {
        assertThat(OfficeController.stripTrailingSlash("http://x"))
                .isEqualTo("http://x");
        assertThat(OfficeController.stripTrailingSlash(""))
                .isEqualTo("");
        assertThat(OfficeController.stripTrailingSlash(null))
                .isEqualTo("");
    }
}
