package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FrontMatter} — the fenced {@code --- key: value ---}
 * splitter shared by markdown / plain-text documents and the
 * {@code vance-input} block's header config.
 */
class FrontMatterTest {

    @Test
    void parse_headerAndBody_splitsBothWays() {
        FrontMatter fm = FrontMatter.parse("---\nonSave: update.js\nsession: true\n---\nhello world\n");

        assertThat(fm.hasHeader()).isTrue();
        assertThat(fm.get("onSave")).isEqualTo("update.js");
        assertThat(fm.getBoolean("session")).isTrue();
        assertThat(fm.body()).isEqualTo("hello world\n");
    }

    @Test
    void parse_noFence_isHeaderlessWithFullBody() {
        FrontMatter fm = FrontMatter.parse("just plain text\nno header\n");

        assertThat(fm.hasHeader()).isFalse();
        assertThat(fm.body()).isEqualTo("just plain text\nno header\n");
        assertThat(fm.get("onSave")).isNull();
    }

    @Test
    void parse_unterminatedFence_isHeaderless() {
        FrontMatter fm = FrontMatter.parse("---\nonSave: update.js\nnever closed\n");

        assertThat(fm.hasHeader()).isFalse();
        assertThat(fm.body()).isEqualTo("---\nonSave: update.js\nnever closed\n");
    }

    @Test
    void get_isCaseInsensitive() {
        FrontMatter fm = FrontMatter.parse("---\nOnSave: x.js\n---\nbody");

        assertThat(fm.get("onsave")).isEqualTo("x.js");
        assertThat(fm.get("ONSAVE")).isEqualTo("x.js");
    }

    @Test
    void render_headerless_returnsBodyVerbatim() {
        FrontMatter fm = FrontMatter.parse("plain body\n");

        assertThat(fm.render()).isEqualTo("plain body\n");
    }

    @Test
    void setBody_preservesHeaderOnRender() {
        FrontMatter fm = FrontMatter.parse("---\nonSave: update.js\n---\nold body");
        fm.setBody("new body");

        assertThat(fm.render()).isEqualTo("---\nonSave: update.js\n---\nnew body");
    }

    @Test
    void set_upsertsKeyPreservingCasing() {
        FrontMatter fm = FrontMatter.parse("---\nonSave: a.js\n---\nbody");
        fm.set("onsave", "b.js");

        // Original key casing is kept, value replaced.
        assertThat(fm.render()).isEqualTo("---\nonSave: b.js\n---\nbody");
    }

    @Test
    void set_blankValueRemovesKey() {
        FrontMatter fm = FrontMatter.parse("---\nonSave: a.js\nsession: true\n---\nbody");
        fm.set("onSave", null);

        assertThat(fm.get("onSave")).isNull();
        assertThat(fm.render()).isEqualTo("---\nsession: true\n---\nbody");
    }

    @Test
    void set_onHeaderlessDoc_growsAHeader() {
        FrontMatter fm = FrontMatter.parse("just content");
        fm.set("onSave", "update.js");
        fm.setBoolean("session", true);

        assertThat(fm.render()).isEqualTo("---\nonSave: update.js\nsession: true\n---\njust content");
    }

    @Test
    void setBoolean_falseClearsKey() {
        FrontMatter fm = FrontMatter.parse("---\nsession: true\n---\nbody");
        fm.setBoolean("session", false);

        assertThat(fm.get("session")).isNull();
        assertThat(fm.render()).isEqualTo("body");
    }
}
