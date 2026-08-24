package de.mhus.vance.shared.document.jaglan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which half of a query string belongs to the source and which to us.
 *
 * <p>The failure this guards is not an exception: a forwarded {@code kind=}
 * would be read by the source instead of by the reference grammar, and a
 * forwarded {@code download=true} is a word the source never asked for.
 */
class MountQueryTest {

    @Test
    void forward_nullOrBlank_isNull() {
        assertThat(MountQuery.forward(null)).isNull();
        assertThat(MountQuery.forward("")).isNull();
        assertThat(MountQuery.forward("   ")).isNull();
    }

    @Test
    void forward_keepsSourceParametersVerbatim() {
        // Verbatim matters: the value is already percent-encoded, and
        // re-encoding would turn two parameters into one opaque string.
        assertThat(MountQuery.forward("from=2026-01&to=2026-06%2F30"))
                .isEqualTo("from=2026-01&to=2026-06%2F30");
    }

    @Test
    void forward_dropsReservedNamesRatherThanRefusing() {
        // download= is legitimately present on every download link; refusing
        // it would break the ordinary case.
        assertThat(MountQuery.forward("download=true&from=2026-01"))
                .isEqualTo("from=2026-01");
        assertThat(MountQuery.forward("kind=chart&from=2026-01"))
                .isEqualTo("from=2026-01");
    }

    @Test
    void forward_onlyReservedNames_isNullNotEmpty() {
        // Null, because everything downstream reads null as "a plain read".
        // An empty string would take the parameterised branch for a query that
        // has no parameters in it — and then be refused by a mount that serves
        // no parameters, for a plain download.
        assertThat(MountQuery.forward("download=true")).isNull();
        assertThat(MountQuery.forward("kind=chart&download=false")).isNull();
    }

    @Test
    void forward_reservedNameMatchIsCaseInsensitive() {
        assertThat(MountQuery.forward("Download=true&a=1")).isEqualTo("a=1");
    }

    @Test
    void forward_valuelessParameter_survives() {
        // `?refresh` without a value is a legitimate flag on a foreign API.
        assertThat(MountQuery.forward("refresh&a=1")).isEqualTo("refresh&a=1");
    }

    @Test
    void forward_doesNotConfuseAValueWithAKey() {
        // A reserved word appearing as a *value* is none of our business.
        assertThat(MountQuery.forward("field=kind&a=1")).isEqualTo("field=kind&a=1");
    }

    @Test
    void hasForwardable_agreesWithForward() {
        assertThat(MountQuery.hasForwardable("download=true")).isFalse();
        assertThat(MountQuery.hasForwardable("from=1")).isTrue();
    }
}
