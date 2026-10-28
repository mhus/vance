package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NpmPackageSpecTest {

    @Test
    void parse_bareName_hasNoRange() {
        NpmPackageSpec spec = NpmPackageSpec.parse("lodash");

        assertThat(spec.name()).isEqualTo("lodash");
        assertThat(spec.range()).isNull();
        assertThat(spec.toArgument()).isEqualTo("lodash");
    }

    @Test
    void parse_nameWithRange_splitsAtVersionSeparator() {
        NpmPackageSpec spec = NpmPackageSpec.parse("lodash@^4.17.0");

        assertThat(spec.name()).isEqualTo("lodash");
        assertThat(spec.range()).isEqualTo("^4.17.0");
        assertThat(spec.toArgument()).isEqualTo("lodash@^4.17.0");
    }

    @Test
    void parse_scopedNameWithRange_keepsScope() {
        NpmPackageSpec spec = NpmPackageSpec.parse("@types/node@>=20 <23");

        assertThat(spec.name()).isEqualTo("@types/node");
        assertThat(spec.range()).isEqualTo(">=20 <23");
    }

    @Test
    void parse_distTag_isAccepted() {
        assertThat(NpmPackageSpec.parse("react@latest").range()).isEqualTo("latest");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:/etc",
                "file:../host/pkg.tgz",
                "/tmp/pkg",
                "./pkg",
                "../pkg",
                "git+ssh://git@github.com/x/y.git",
                "github:x/y",
                "https://example.com/pkg.tgz",
                "lodash@npm:evil",
                "lodash@file:/etc",
                "lodash@https://example.com/x.tgz",
                "--registry=http://127.0.0.1/",
                "-g",
                "Lodash",
                "",
                "lodash@"
            })
    void parse_nonRegistrySpec_isRejected(String raw) {
        assertThatThrownBy(() -> NpmPackageSpec.parse(raw)).isInstanceOf(RootDirException.class);
    }

    @Test
    void parse_nameLongerThanNpmLimit_isRejected() {
        assertThatThrownBy(() -> NpmPackageSpec.parse("a".repeat(215))).isInstanceOf(RootDirException.class);
    }
}
