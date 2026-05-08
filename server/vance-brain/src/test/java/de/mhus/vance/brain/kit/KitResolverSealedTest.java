package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code sealed: true} enforcement in
 * {@link KitResolver#collectInherits} — spec: kits.md §3.2.
 *
 * <p>The resolver throws before any merge happens, so the rest of the
 * pipeline can be exercised entirely with mocks. We do NOT touch the
 * filesystem in this test.
 */
class KitResolverSealedTest {

    private KitRepoLoader repoLoader;
    private KitWorkspace workspace;
    private KitResolver resolver;

    @BeforeEach
    void setUp() {
        repoLoader = mock(KitRepoLoader.class);
        workspace = mock(KitWorkspace.class);

        // Workspace hands out throwaway paths — the test never reaches
        // any code that reads them, so non-existent paths are fine.
        when(workspace.allocate(any())).thenReturn(Paths.get("/tmp/kit-fake"));

        resolver = new KitResolver(repoLoader, workspace);
    }

    @Test
    void resolve_inheritIsSealed_throwsWithSealedKitName() {
        KitInheritDto topSource = KitInheritDto.builder().url("file:///top").build();
        KitInheritDto sealedInherit = KitInheritDto.builder().url("file:///sealed-base").build();

        KitDescriptorDto topDescriptor = KitDescriptorDto.builder()
                .name("derived")
                .description("derived from a sealed base")
                .inherits(List.of(sealedInherit))
                .build();
        KitDescriptorDto sealedDescriptor = KitDescriptorDto.builder()
                .name("locked-base")
                .description("a sealed base")
                .sealed(true)
                .build();

        when(repoLoader.load(eq(topSource), any(), any()))
                .thenReturn(loaded(topDescriptor));
        when(repoLoader.load(eq(sealedInherit), any(), any()))
                .thenReturn(loaded(sealedDescriptor));

        assertThatThrownBy(() -> resolver.resolve(topSource, null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("locked-base")
                .hasMessageContaining("sealed");
    }

    @Test
    void resolve_topLayerIsSealedButHasNoInherits_isFineHere() {
        // Sealing only restricts being-inherited-from. The resolver
        // does NOT block a sealed kit at the top level — that's the
        // job of KitService for installable=false; sealed has no
        // top-layer effect. With no inherits, resolve() must succeed.
        KitInheritDto src = KitInheritDto.builder().url("file:///top").build();
        KitDescriptorDto sealedTop = KitDescriptorDto.builder()
                .name("end-product")
                .description("sealed but installable")
                .sealed(true)
                .build();
        when(repoLoader.load(eq(src), any(), any())).thenReturn(loaded(sealedTop));

        // Just calling resolve must not throw the sealed exception.
        // Note: we mock workspace.allocate to a fake path, so the
        // subsequent mergeLayer call will fail on its own — but with a
        // different exception (filesystem-not-found wrapped in
        // KitException). The point of this test is that the *sealed*
        // exception is NOT raised for top layers.
        assertThatThrownBy(() -> resolver.resolve(src, null))
                .isInstanceOf(KitException.class)
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .doesNotContain("cannot be inherited from");
    }

    private static KitRepoLoader.LoadedKit loaded(KitDescriptorDto descriptor) {
        Path fake = Paths.get("/tmp/kit-fake");
        return new KitRepoLoader.LoadedKit(fake, fake, "deadbeef", descriptor, true);
    }

    @SuppressWarnings("unused")
    private static List<KitInheritDto> noInherits() {
        return new ArrayList<>();
    }
}
