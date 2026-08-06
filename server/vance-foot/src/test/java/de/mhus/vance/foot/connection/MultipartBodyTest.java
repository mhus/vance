package de.mhus.vance.foot.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hand-rolled multipart body — {@code HttpRequest} has no publisher
 * for it. Wrong framing here fails as an opaque 400 from the brain, so
 * the wire shape is worth pinning down.
 */
class MultipartBodyTest {

    @TempDir
    Path tempDir;

    private String bodyOf(Path file, String... fields) throws IOException {
        return new String(
                BrainRestClientService.multipartBody("BOUND", file, "file", fields),
                StandardCharsets.UTF_8);
    }

    @Test
    void carriesTextFieldsAndTheFileWithItsName() throws IOException {
        Path file = Files.writeString(tempDir.resolve("shot.png"), "PNGDATA");

        String body = bodyOf(file, "path", "_chatbox/ab12_shot.png");

        assertThat(body)
                .contains("--BOUND")
                .contains("Content-Disposition: form-data; name=\"path\"")
                .contains("_chatbox/ab12_shot.png")
                .contains("Content-Disposition: form-data; name=\"file\"; filename=\"shot.png\"")
                .contains("PNGDATA")
                .endsWith("--BOUND--\r\n");
    }

    @Test
    void fileIsTheLastPart_soFieldsAreParsedBeforeIt() throws IOException {
        Path file = Files.writeString(tempDir.resolve("a.txt"), "BODY");

        String body = bodyOf(file, "path", "x/y.txt");

        assertThat(body.indexOf("name=\"path\"")).isLessThan(body.indexOf("name=\"file\""));
    }

    @Test
    void binaryContentSurvivesUnmangled() throws IOException {
        byte[] bytes = {0, 1, 2, (byte) 0x89, 'P', 'N', 'G', (byte) 0xFF};
        Path file = tempDir.resolve("bin.png");
        Files.write(file, bytes);

        byte[] body = BrainRestClientService.multipartBody("B", file, "file");

        // The payload must appear verbatim — a text round-trip would
        // have destroyed the high bytes.
        assertThat(indexOf(body, bytes)).isGreaterThan(0);
    }

    @Test
    void oddNumberOfFieldTokens_isRejected() throws IOException {
        Path file = Files.writeString(tempDir.resolve("a.txt"), "x");

        assertThatThrownBy(() ->
                BrainRestClientService.multipartBody("B", file, "file", "lonely"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternate");
    }

    @Test
    void noFields_stillProducesAValidSinglePartBody() throws IOException {
        Path file = Files.writeString(tempDir.resolve("a.txt"), "x");

        String body = bodyOf(file);

        assertThat(body).startsWith("--B").contains("filename=\"a.txt\"");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
