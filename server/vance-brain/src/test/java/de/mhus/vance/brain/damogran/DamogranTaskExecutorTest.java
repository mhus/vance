package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DamogranTaskExecutorTest {

    private static DamogranContext ctx() {
        return new DamogranContext("t", "p", "proc1", "ws", "ws", Path.of("/tmp/ws"), "WORK", null, null);
    }

    private static TaskSpec task(String type) {
        return new TaskSpec(type, Map.of(), List.of());
    }

    private record FixedTask(String type, DamogranTaskResult result) implements DamogranTask {
        @Override
        public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
            return result;
        }
    }

    @Test
    void dispatch_knownType_returnsTaskResult() {
        DamogranTaskResult ok = DamogranTaskResult.success(List.of(OutputArtifact.of("out.txt")));
        DamogranTaskExecutor executor = new DamogranTaskExecutor(List.of(new FixedTask("exec", ok)));

        DamogranTaskResult result = executor.dispatch(ctx(), task("exec"));

        assertThat(result).isSameAs(ok);
    }

    @Test
    void dispatch_unknownType_returnsFailureListingKnownTypes() {
        DamogranTaskExecutor executor = new DamogranTaskExecutor(
                List.of(new FixedTask("exec", DamogranTaskResult.success(List.of()))));

        DamogranTaskResult result = executor.dispatch(ctx(), task("python"));

        assertThat(result.status()).isEqualTo(DamogranStatus.FAILURE);
        assertThat(result.error()).contains("unknown task type 'python'").contains("exec");
    }

    @Test
    void dispatch_taskThrows_becomesFailure() {
        DamogranTask throwing = new DamogranTask() {
            @Override
            public String type() {
                return "boom";
            }

            @Override
            public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
                throw new IllegalStateException("kaboom");
            }
        };
        DamogranTaskExecutor executor = new DamogranTaskExecutor(List.of(throwing));

        DamogranTaskResult result = executor.dispatch(ctx(), task("boom"));

        assertThat(result.status()).isEqualTo(DamogranStatus.FAILURE);
        assertThat(result.error()).contains("boom").contains("kaboom");
    }

    @Test
    void construct_duplicateType_throws() {
        assertThatThrownBy(() -> new DamogranTaskExecutor(List.of(
                new FixedTask("exec", DamogranTaskResult.success(List.of())),
                new FixedTask("exec", DamogranTaskResult.success(List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate DamogranTask type 'exec'");
    }
}
