package de.mhus.vance.foot.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Paging and capping behaviour of {@code client_file_read}.
 *
 * <p>Regression cover for the 2026-08-11 loop: a worker read a 44 KB file,
 * got the capped 8 000-char prefix back, and re-tried with a larger
 * {@code maxChars} three times — each time receiving the byte-identical
 * prefix, because the generic wrapper's {@code maxChars} was never read on
 * the CLIENT path. What it needed was the line window. Both parameters now
 * work here, so "the result is truncated" has an answer that changes the
 * result.
 */
class ClientFileReadToolPagingTest {

    private Path root;
    private final ClientFileReadTool tool = new ClientFileReadTool();

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-client-read-paging-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /** 400 lines of "line-N", ~4 KB total. */
    private Path bigFile() throws IOException {
        String body = IntStream.rangeClosed(1, 400)
                .mapToObj(i -> "line-" + i)
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
        Path p = root.resolve("big.txt");
        Files.writeString(p, body, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void lineWindow_reachesTheMiddleOfTheFile() throws IOException {
        Path p = bigFile();

        Map<String, Object> out = tool.invoke(Map.of(
                "path", p.toString(), "startLine", 300, "maxLines", 3));

        assertThat(out.get("content")).isEqualTo("line-300\nline-301\nline-302");
        assertThat(out.get("truncated")).isEqualTo(false);
    }

    @Test
    void maxChars_isHonoured_notJustTheDefaultCap() throws IOException {
        Path p = bigFile();

        Map<String, Object> out = tool.invoke(Map.of(
                "path", p.toString(), "maxChars", 20));

        // The whole point: a caller-supplied cap changes the result. Passing
        // maxChars used to be a no-op on this backend.
        assertThat((String) out.get("content")).hasSize(20);
        assertThat(out.get("truncated")).isEqualTo(true);
    }

    @Test
    void wideLineWindow_isStillCapped() throws IOException {
        Path p = bigFile();

        Map<String, Object> out = tool.invoke(Map.of(
                "path", p.toString(), "startLine", 1, "maxLines", 400, "maxChars", 50));

        // A line window is just as capable of returning a megabyte as an
        // uncapped whole-file read, so the cap applies to both paths.
        assertThat((String) out.get("content")).hasSize(50);
        assertThat(out.get("truncated")).isEqualTo(true);
    }

    @Test
    void totalChars_describesTheRequestedRegion_soTruncatedIsComparable() throws IOException {
        Path p = bigFile();
        int fileChars = Files.readString(p, StandardCharsets.UTF_8).length();

        Map<String, Object> whole = tool.invoke(Map.of("path", p.toString()));
        Map<String, Object> window = tool.invoke(Map.of(
                "path", p.toString(), "startLine", 1, "maxLines", 2));

        assertThat(whole.get("totalChars")).isEqualTo(fileChars);
        // Window read: totalChars counts the window ("line-1\nline-2"), which
        // is what its truncated flag refers to.
        assertThat(window.get("totalChars")).isEqualTo("line-1\nline-2".length());
    }

    @Test
    void smallFile_isReturnedWhole_andNotFlaggedTruncated() throws IOException {
        Path p = root.resolve("small.txt");
        Files.writeString(p, "hello", StandardCharsets.UTF_8);

        Map<String, Object> out = tool.invoke(Map.of("path", p.toString()));

        assertThat(out.get("content")).isEqualTo("hello");
        assertThat(out.get("truncated")).isEqualTo(false);
        assertThat(out.get("totalChars")).isEqualTo(5);
    }
}
