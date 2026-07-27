package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvFileTest {

    @Test
    void read_parsesPairs_stripsQuotesAndComments(@TempDir Path dir) throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # comment
                FOO=bar

                QUOTED="with space"
                SINGLE='single'
                EMPTY=
                """, StandardCharsets.UTF_8);

        Map<String, String> parsed = DotEnvFile.read(env);

        assertThat(parsed).containsEntry("FOO", "bar")
                .containsEntry("QUOTED", "with space")
                .containsEntry("SINGLE", "single")
                .containsEntry("EMPTY", "");
        assertThat(parsed).doesNotContainKey("# comment");
    }

    @Test
    void read_missingFile_returnsEmpty(@TempDir Path dir) throws IOException {
        assertThat(DotEnvFile.read(dir.resolve("nope.env"))).isEmpty();
    }

    @Test
    void render_carriesOverUnmanagedKeys() {
        Map<String, String> managed = new LinkedHashMap<>();
        managed.put("IMAGE_TAG", "latest");
        Map<String, String> existing = new LinkedHashMap<>();
        existing.put("IMAGE_TAG", "old");          // managed → replaced, not duplicated
        existing.put("MY_CUSTOM_KEY", "keepme");    // unmanaged → preserved

        String body = DotEnvFile.render(managed, existing);

        assertThat(body).contains("IMAGE_TAG=latest");
        assertThat(body).contains("MY_CUSTOM_KEY=keepme");
        assertThat(body).doesNotContain("IMAGE_TAG=old");
        assertThat(body).contains("carried over");
    }

    @Test
    void render_bcryptHashWrittenVerbatim() {
        Map<String, String> managed = new LinkedHashMap<>();
        String hash = "$2a$10$abcdefghijklmnopqrstuv";
        managed.put("VANCE_ANUS_PASSWORD_HASH", hash);

        String body = DotEnvFile.render(managed, Map.of());

        assertThat(body).contains("VANCE_ANUS_PASSWORD_HASH=" + hash);
    }
}
