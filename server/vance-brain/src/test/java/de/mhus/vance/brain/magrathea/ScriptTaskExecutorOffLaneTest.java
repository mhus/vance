package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.action.ScriptActionExecutor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScriptTaskExecutorOffLaneTest {

    @Test
    void aScriptRunsOffTheLaneAndHasNoProgramToKillBeyondTheInterrupt() {
        ScriptTaskExecutor executor = new ScriptTaskExecutor(
                mock(ScriptActionExecutor.class), JsonMapper.builder().build());

        assertThat(executor.runsOffLane()).isTrue();
    }
}
