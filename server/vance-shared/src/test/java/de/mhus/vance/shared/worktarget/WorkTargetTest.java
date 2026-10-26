package de.mhus.vance.shared.worktarget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkTargetTest {

    @Test
    void none_roundTripsThroughTheEngineParamsMap() {
        Map<String, Object> map = WorkTarget.none().toMap();

        assertThat(map).containsExactly(Map.entry(WorkTarget.FIELD_KIND, "NONE"));
        assertThat(WorkTarget.fromMap(map)).isEqualTo(WorkTarget.none());
    }

    @Test
    void fromMap_lowercaseNone_isAccepted() {
        assertThat(WorkTarget.fromMap(Map.of(WorkTarget.FIELD_KIND, "none")).kind())
                .isEqualTo(WorkTargetKind.NONE);
    }
}
