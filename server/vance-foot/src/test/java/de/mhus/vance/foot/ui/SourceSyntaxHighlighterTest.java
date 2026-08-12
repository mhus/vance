package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

class SourceSyntaxHighlighterTest {

    private final FootConfig.SyntaxHighlight config = palette();
    private final SourceSyntaxHighlighter highlighter = new SourceSyntaxHighlighter(config);

    @Test
    void detectsLanguagesFromPathsAndFenceNames() {
        assertThat(SourceLanguage.fromPath("src/Main.java")).isEqualTo(SourceLanguage.JAVA);
        assertThat(SourceLanguage.fromPath("tools/run.py")).isEqualTo(SourceLanguage.PYTHON);
        assertThat(SourceLanguage.fromPath("tools/deploy.sh")).isEqualTo(SourceLanguage.SHELL);
        assertThat(SourceLanguage.fromPath("~/.bashrc")).isEqualTo(SourceLanguage.SHELL);
        assertThat(SourceLanguage.fromPath("README.md")).isNull();
        assertThat(SourceLanguage.fromFence("java")).isEqualTo(SourceLanguage.JAVA);
        assertThat(SourceLanguage.fromFence("python")).isEqualTo(SourceLanguage.PYTHON);
        assertThat(SourceLanguage.fromFence("bash title=deploy")).isEqualTo(SourceLanguage.SHELL);
        assertThat(SourceLanguage.fromFence("sh")).isEqualTo(SourceLanguage.SHELL);
        assertThat(SourceLanguage.fromFence("shell")).isEqualTo(SourceLanguage.SHELL);
        assertThat(SourceLanguage.fromFence("text")).isNull();
    }

    @Test
    void highlightsJavaTokensAndKeepsBlockCommentState() {
        SourceSyntaxHighlighter.State state = new SourceSyntaxHighlighter.State();
        AttributedString first = highlighter.highlight("public String value = \"x\"; /* note", SourceLanguage.JAVA, state);
        AttributedString second = highlighter.highlight("continued */ return 42;", SourceLanguage.JAVA, state);

        assertThat(foregroundAt(first, "public")).isEqualTo(1);
        assertThat(foregroundAt(first, "\"x\"")).isEqualTo(2);
        assertThat(foregroundAt(first, "/* note")).isEqualTo(3);
        assertThat(foregroundAt(second, "continued")).isEqualTo(3);
        assertThat(foregroundAt(second, "return")).isEqualTo(1);
        assertThat(foregroundAt(second, "42")).isEqualTo(4);
    }

    @Test
    void keepsPythonTripleStringStateAndResumesKeywords() {
        SourceSyntaxHighlighter.State state = new SourceSyntaxHighlighter.State();
        AttributedString first = highlighter.highlight("text = \"\"\"hello", SourceLanguage.PYTHON, state);
        AttributedString second = highlighter.highlight("world\"\"\"\n", SourceLanguage.PYTHON, state);
        AttributedString third = highlighter.highlight("return 7 # done", SourceLanguage.PYTHON, state);

        assertThat(foregroundAt(first, "\"\"\"hello")).isEqualTo(2);
        assertThat(foregroundAt(second, "world")).isEqualTo(2);
        assertThat(foregroundAt(third, "return")).isEqualTo(1);
        assertThat(foregroundAt(third, "7")).isEqualTo(4);
        assertThat(foregroundAt(third, "# done")).isEqualTo(3);
    }

    @Test
    void highlightsShellTokensVariablesCommentsAndKeepsQuoteState() {
        SourceSyntaxHighlighter.State state = new SourceSyntaxHighlighter.State();
        AttributedString first = highlighter.highlight(
                "if test -n \"$HOME", SourceLanguage.SHELL, state);
        AttributedString second = highlighter.highlight(
                "/bin\"; then echo ${USER} $(pwd) # greeting", SourceLanguage.SHELL, state);

        assertThat(foregroundAt(first, "if")).isEqualTo(1);
        assertThat(foregroundAt(first, "test")).isEqualTo(5);
        assertThat(foregroundAt(first, "\"$HOME")).isEqualTo(2);
        assertThat(foregroundAt(second, "/bin\"")).isEqualTo(2);
        assertThat(foregroundAt(second, "then")).isEqualTo(1);
        assertThat(foregroundAt(second, "echo")).isEqualTo(5);
        assertThat(foregroundAt(second, "${USER}")).isEqualTo(5);
        assertThat(foregroundAt(second, "$(pwd)")).isEqualTo(5);
        assertThat(foregroundAt(second, "# greeting")).isEqualTo(3);
    }

    private static int foregroundAt(AttributedString text, String token) {
        int at = text.toString().indexOf(token);
        assertThat(at).isGreaterThanOrEqualTo(0);
        return (int) ((text.styleAt(at).getStyle() >>> 15) & 0xffffff);
    }

    private static FootConfig.SyntaxHighlight palette() {
        FootConfig.SyntaxHighlight cfg = new FootConfig.SyntaxHighlight();
        cfg.setKeyword("fg:red");
        cfg.setString("fg:green");
        cfg.setComment("fg:yellow");
        cfg.setNumber("fg:blue");
        cfg.setAnnotation("fg:magenta");
        return cfg;
    }
}
