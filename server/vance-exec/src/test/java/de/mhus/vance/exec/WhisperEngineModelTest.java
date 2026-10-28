package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WhisperEngineModelTest {

    @Test
    void aModelThatIsNotAPlainNameNeverReachesTheChild() throws Exception {
        WhisperEngine engine = new WhisperEngine("python3", spec -> {
            throw new AssertionError("the child must not be started");
        });

        for (String model : new String[] {"../../etc/model", "/abs/path", "Systran/faster-whisper-small", "-x"}) {
            assertThatThrownBy(() -> engine.transcribe(Path.of("a.mp3"), model, null, null))
                    .as(model)
                    .isInstanceOfSatisfying(
                            MechException.class,
                            e -> assertThat(e.reason()).isEqualTo(MechException.Reason.PARAMETER_INVALID));
        }
    }
}
