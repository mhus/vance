package de.mhus.vance.brain.wowbagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TargetFileStreamTest {

    /** {@code file_read} over fixed lines, honouring startLine/maxLines/maxChars like the real tool. */
    private static Function<Map<String, Object>, Map<String, Object>> fileOf(List<String> lines, List<Integer> pages) {
        return params -> {
            int start = ((Number) params.get("startLine")).intValue();
            int max = ((Number) params.get("maxLines")).intValue();
            int cap = ((Number) params.get("maxChars")).intValue();
            pages.add(start);
            String window =
                    String.join("\n", lines.stream().skip(start - 1L).limit(max).toList());
            Map<String, Object> out = new LinkedHashMap<>();
            if (window.length() > cap) {
                out.put("content", window.substring(0, cap));
                out.put("truncated", true);
            } else {
                out.put("content", window);
                out.put("truncated", false);
            }
            return out;
        };
    }

    private static String readAll(TargetFileStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void readsPagesInOrderAndTerminatesNewlineSeparated() throws IOException {
        List<Integer> pages = new ArrayList<>();
        List<String> lines = List.of("a", "b", "c", "d", "e");

        String text = readAll(new TargetFileStream("f.txt", fileOf(lines, pages), 2, 1000));

        assertThat(text).isEqualTo("a\nb\nc\nd\ne\n");
        assertThat(pages).containsExactly(1, 3, 5);
    }

    @Test
    void aPageCutByTheCharCapContinuesAtTheCutLine() throws IOException {
        List<Integer> pages = new ArrayList<>();
        List<String> lines = List.of("aaaa", "bbbb", "cccc", "dddd");

        // cap 11 cuts "aaaa\nbbbb\ncccc" inside the third line
        String text = readAll(new TargetFileStream("f.txt", fileOf(lines, pages), 10, 11));

        assertThat(text).isEqualTo("aaaa\nbbbb\ncccc\ndddd\n");
    }

    @Test
    void anEmptyFileYieldsNothing() throws IOException {
        assertThat(readAll(new TargetFileStream("f.txt", fileOf(List.of(), new ArrayList<>()), 10, 100)))
                .isEmpty();
    }

    @Test
    void aLineLongerThanThePageCapIsAnError() {
        TargetFileStream in = new TargetFileStream("f.txt", fileOf(List.of("x".repeat(50)), new ArrayList<>()), 10, 20);

        assertThatThrownBy(() -> readAll(in)).isInstanceOf(IOException.class).hasMessageContaining("longer than");
    }

    @Test
    void aFailingToolBecomesAnIoException() {
        TargetFileStream in = new TargetFileStream("gone.txt", params -> {
            throw new IllegalStateException("file not found");
        });

        assertThatThrownBy(() -> readAll(in))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gone.txt")
                .hasMessageContaining("file not found");
    }
}
