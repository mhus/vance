package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.damogran.DamogranManifest.StateSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * State-store lifecycle against a real temp workspace, with {@link WorkspaceService}
 * mocked as a pass-through to that dir (its own confinement is tested elsewhere).
 */
class DamogranStateServiceTest {

    @TempDir
    Path workspaceRoot;

    private DamogranStateService service(WorkspaceService ws) {
        // resolve(t,p,dir,rel) → workspaceRoot/rel ; write(...) actually writes.
        when(ws.resolve(eq("t"), eq("p"), eq("ws"), anyString()))
                .thenAnswer(inv -> workspaceRoot.resolve((String) inv.getArgument(3)));
        when(ws.write(eq("t"), eq("p"), eq("ws"), anyString(), any()))
                .thenAnswer(inv -> {
                    Path p = workspaceRoot.resolve((String) inv.getArgument(3));
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, (String) inv.getArgument(4), StandardCharsets.UTF_8);
                    return p;
                });
        return new DamogranStateService(ws);
    }

    private DamogranContext ctx() {
        return new DamogranContext("t", "p", "proc", "ws", "ws", workspaceRoot,
                "WORK", null, null, "docs/report", null, null, null, null, null);
    }

    @Test
    void applyOps_initAndHeader_createsTypeFolderWithHeaderNoCache() {
        DamogranStateService state = service(mock(WorkspaceService.class));

        state.applyOps(ctx(), List.of(
                new StateSpec("python", true, "import os", null, false)));

        DamogranStateService.StateDir sd = state.resolve(ctx(), "python");
        assertThat(sd).isNotNull();
        assertThat(sd.readHeader()).isEqualTo("import os");
        assertThat(sd.readFooter()).isEmpty();
        assertThat(sd.existsCache()).isFalse();
        assertThat(sd.cacheRel()).isEqualTo("_damogran-state/docs/report/python/cache.json");
    }

    @Test
    void resolve_noOpForTypeWithoutFolder_returnsNull() {
        DamogranStateService state = service(mock(WorkspaceService.class));
        state.applyOps(ctx(), List.of(new StateSpec("python", true, null, null, false)));

        // python folder exists, exec does not → exec runs plain (null).
        assertThat(state.resolve(ctx(), "python")).isNotNull();
        assertThat(state.resolve(ctx(), "exec")).isNull();
    }

    @Test
    void applyOps_deleteWipesWholeStore() throws IOException {
        DamogranStateService state = service(mock(WorkspaceService.class));
        state.applyOps(ctx(), List.of(
                new StateSpec("python", true, "h", null, false),
                new StateSpec("exec", true, null, null, false)));
        // drop a cache file to prove content is gone too
        Files.writeString(workspaceRoot.resolve("_damogran-state/docs/report/python/cache.json"), "{}");

        state.applyOps(ctx(), List.of(new StateSpec(null, false, null, null, true)));

        assertThat(Files.exists(workspaceRoot.resolve("_damogran-state/docs/report"))).isFalse();
        assertThat(state.resolve(ctx(), "python")).isNull();
    }

    @Test
    void applyOps_initClearsPriorCacheButKeepsOtherTypes() throws IOException {
        DamogranStateService state = service(mock(WorkspaceService.class));
        state.applyOps(ctx(), List.of(
                new StateSpec("python", true, null, null, false),
                new StateSpec("exec", true, null, null, false)));
        Path pyCache = workspaceRoot.resolve("_damogran-state/docs/report/python/cache.json");
        Files.writeString(pyCache, "{\"x\":1}");

        // re-init only python → its cache is gone, exec folder untouched
        state.applyOps(ctx(), List.of(new StateSpec("python", true, null, null, false)));

        assertThat(Files.exists(pyCache)).isFalse();
        assertThat(state.resolve(ctx(), "python")).isNotNull();
        assertThat(state.resolve(ctx(), "exec")).isNotNull();
    }

    @Test
    void applyOps_noStateKey_isNoOp() {
        DamogranStateService state = service(mock(WorkspaceService.class));
        DamogranContext noKey = new DamogranContext("t", "p", "proc", "ws", "ws", workspaceRoot,
                "WORK", null, null, /* stateKey */ null, null, null, null, null, null);

        state.applyOps(noKey, List.of(new StateSpec("python", true, "h", null, false)));

        assertThat(state.resolve(noKey, "python")).isNull();
        assertThat(Files.exists(workspaceRoot.resolve("_damogran-state"))).isFalse();
    }

    @Test
    void jsonQuote_escapesForEmbedding() {
        assertThat(DamogranStateService.jsonQuote("a/b.json")).isEqualTo("\"a/b.json\"");
        assertThat(DamogranStateService.jsonQuote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }
}
