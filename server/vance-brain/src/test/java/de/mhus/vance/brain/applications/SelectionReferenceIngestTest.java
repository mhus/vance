package de.mhus.vance.brain.applications;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.SelectionReference;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import org.junit.jupiter.api.Test;

/**
 * The gate between what an app remote declares and what gets written into
 * a chat message that every later prompt replays. Two things it has to get
 * right: foreign text must not be able to start a line of its own inside a
 * Markdown-shaped prompt, and a "reference" that cannot be followed must
 * not be persisted at all.
 */
class SelectionReferenceIngestTest {

    @Test
    void keepsLabelAndBothAddresses() {
        SelectionReference out = SelectionReferenceIngest.sanitize(ref(
                "Dolly Parton and Ireland",
                "vance:/apps/newsfeed/_app.yaml?entry=hrafnagud%2F42",
                "https://irishcentral.com/culture"));

        assertThat(out).isNotNull();
        assertThat(out.getLabel()).isEqualTo("Dolly Parton and Ireland");
        assertThat(out.getVanceUri())
                .isEqualTo("vance:/apps/newsfeed/_app.yaml?entry=hrafnagud%2F42");
        assertThat(out.getUrl()).isEqualTo("https://irishcentral.com/culture");
    }

    // A headline is written by a foreign archive. Left alone, a newline in
    // it would open a heading inside the prompt line that carries it.
    @Test
    void collapsesWhitespaceInTheLabel() {
        SelectionReference out = SelectionReferenceIngest.sanitize(
                ref("two\n\n## lines", null, "https://example.com/a"));

        assertThat(out.getLabel()).isEqualTo("two ## lines");
    }

    @Test
    void capsAnOverlongLabel() {
        SelectionReference out = SelectionReferenceIngest.sanitize(
                ref("x".repeat(500), null, "https://example.com/a"));

        assertThat(out.getLabel()).hasSizeLessThanOrEqualTo(
                SelectionReferenceIngest.MAX_LABEL_CHARS + 1);
        assertThat(out.getLabel()).endsWith("…");
    }

    @Test
    void dropsAReferenceWithoutAnyAddress() {
        assertThat(SelectionReferenceIngest.sanitize(ref("just a name", null, null))).isNull();
    }

    @Test
    void dropsAReferenceWithoutALabel() {
        assertThat(SelectionReferenceIngest.sanitize(ref("   ", null, "https://example.com/a")))
                .isNull();
    }

    // The URL is rendered as a link for a human and offered to the model as
    // something to fetch. Neither role has any use for a script scheme.
    @Test
    void rejectsANonHttpUrl_andWithItTheWholeLabelOnlyReference() {
        assertThat(SelectionReferenceIngest.sanitize(
                ref("evil", null, "javascript:alert(1)"))).isNull();
    }

    @Test
    void rejectsANonHttpUrl_butKeepsTheReferenceWhenAVanceUriRemains() {
        SelectionReference out = SelectionReferenceIngest.sanitize(
                ref("entry", "vance:/apps/x/_app.yaml?entry=a", "javascript:alert(1)"));

        assertThat(out).isNotNull();
        assertThat(out.getUrl()).isNull();
        assertThat(out.getVanceUri()).isEqualTo("vance:/apps/x/_app.yaml?entry=a");
    }

    @Test
    void rejectsAVanceUriThatIsNotOne() {
        assertThat(SelectionReferenceIngest.sanitize(
                ref("entry", "https://evil.example/x", null))).isNull();
    }

    @Test
    void metaForCarriesTheReferenceUnderTheConventionalKey() {
        ActiveAppContext active = ActiveAppContext.builder()
                .folder("apps/newsfeed")
                .app("feeds")
                .selection("hrafnagud/42 — Dolly")
                .selectionRef(ref("Dolly", null, "https://example.com/a"))
                .build();

        var meta = SelectionReferenceIngest.metaFor(active);

        assertThat(meta).containsKey(ChatMessageDocument.META_SELECTION_REFERENCE);
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.getMeta().putAll(meta);
        assertThat(doc.selectionReference()).isNotNull();
        assertThat(doc.selectionReference().getLabel()).isEqualTo("Dolly");
    }

    // Every persist site passes the result of metaFor unconditionally, so
    // "nothing was selected" has to be an empty map rather than null.
    @Test
    void metaForATurnWithoutSelection_isEmpty() {
        assertThat(SelectionReferenceIngest.metaFor(null)).isEmpty();
        assertThat(SelectionReferenceIngest.metaFor(ActiveAppContext.builder()
                .folder("apps/newsfeed").app("feeds").build())).isEmpty();
    }

    private static SelectionReference ref(String label, String vanceUri, String url) {
        return SelectionReference.builder().label(label).vanceUri(vanceUri).url(url).build();
    }
}
