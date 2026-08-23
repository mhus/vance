package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * What happens at the edge of the inherit chain: an inherit that will not
 * load, and an inherit that belongs to somebody else's source.
 *
 * <p>Both used to be quiet. A failed load was appended to a {@code warnings}
 * list nobody reads and the build tree was assembled without that layer, which
 * on {@code update --prune} deleted the layer's documents. And the top layer's
 * credential was handed to every inherit — for an {@code ode} kit the host
 * composes that list <em>per request</em>, so the far end chose where our
 * bearer token went.
 */
class KitResolverInheritTest {

    private static final String TENANT = "acme";

    @TempDir
    Path workspaceDir;

    private KitSourceLoaders sourceLoaders;
    private KitSourceRegistry sources;
    private KitResolver resolver;

    @BeforeEach
    void setUp() {
        sourceLoaders = mock(KitSourceLoaders.class);
        KitWorkspace workspace = mock(KitWorkspace.class);
        when(workspace.allocate(any())).thenReturn(workspaceDir);
        sources = mock(KitSourceRegistry.class);
        resolver = new KitResolver(sourceLoaders, workspace, sources);
    }

    @Test
    void resolve_inheritThatWillNotLoad_abortsInsteadOfWarning() {
        KitInheritDto top = ref("https://git.example/top.git");
        KitInheritDto parent = ref("https://git.example/base.git");
        when(sourceLoaders.loadFrom(any(), eq(top), any()))
                .thenReturn(loadResult(descriptor("top", parent), "house"));
        when(sourceLoaders.load(any(), eq(parent), any()))
                .thenThrow(new KitException("host unreachable"));
        when(sources.resolve(any(), any())).thenReturn(source("house"));

        assertThatThrownBy(() -> resolver.resolve(KitAccess.of(TENANT), top))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("refusing a partial import")
                .hasMessageContaining("base.git");
    }

    @Test
    void resolve_inheritFromAnotherSource_isFetchedWithoutTheTopLayersToken() {
        // The failure this prevents: an ode host answers with
        // `inherits: [{url: https://attacker.example/x.git}]`, and the git
        // loader hands access.token() straight to JGit as a credential.
        KitInheritDto top = ref("https://crm.example/kit");
        KitInheritDto parent = ref("https://attacker.example/x.git");
        when(sourceLoaders.loadFrom(any(), eq(top), any()))
                .thenReturn(loadResult(descriptor("top", parent), "crm"));
        when(sources.resolve(TENANT, "https://crm.example/kit")).thenReturn(source("crm"));
        when(sources.resolve(TENANT, "https://attacker.example/x.git"))
                .thenReturn(source("(unconfigured)"));
        when(sourceLoaders.load(any(), eq(parent), any()))
                .thenThrow(new KitException("stop here — the access object is what we assert"));

        ArgumentCaptor<KitAccess> used = ArgumentCaptor.forClass(KitAccess.class);
        assertThatThrownBy(() -> resolver.resolve(
                KitAccess.of(TENANT).withToken("s3cr3t"), top))
                .isInstanceOf(KitException.class);

        org.mockito.Mockito.verify(sourceLoaders).load(used.capture(), eq(parent), any());
        assertThat(used.getValue().token()).isNull();
    }

    @Test
    void resolve_inheritFromTheSameSource_keepsTheToken() {
        // A host-level source legitimately covers several of its own
        // repositories; scoping by source id rather than by url keeps that
        // working instead of breaking every private inherit.
        KitInheritDto top = ref("https://git.example/top.git");
        KitInheritDto parent = ref("https://git.example/base.git");
        when(sourceLoaders.loadFrom(any(), eq(top), any()))
                .thenReturn(loadResult(descriptor("top", parent), "house"));
        when(sources.resolve(any(), any())).thenReturn(source("house"));
        when(sourceLoaders.load(any(), eq(parent), any()))
                .thenThrow(new KitException("stop here — the access object is what we assert"));

        ArgumentCaptor<KitAccess> used = ArgumentCaptor.forClass(KitAccess.class);
        assertThatThrownBy(() -> resolver.resolve(
                KitAccess.of(TENANT).withToken("s3cr3t"), top))
                .isInstanceOf(KitException.class);

        org.mockito.Mockito.verify(sourceLoaders).load(used.capture(), eq(parent), any());
        assertThat(used.getValue().token()).isEqualTo("s3cr3t");
    }

    private static KitInheritDto ref(String url) {
        return KitInheritDto.builder().url(url).build();
    }

    private static KitDescriptorDto descriptor(String name, KitInheritDto... inherits) {
        return KitDescriptorDto.builder()
                .name(name)
                .description("d")
                .inherits(List.of(inherits))
                .build();
    }

    private static KitSourceDto source(String id) {
        return KitSourceDto.builder()
                .id(id).type(KitSourceType.GIT).url("https://git.example").build();
    }

    private KitSourceLoaders.LoadResult loadResult(KitDescriptorDto descriptor, String sourceId) {
        return new KitSourceLoaders.LoadResult(
                new KitRepoLoader.LoadedKit(
                        workspaceDir, workspaceDir, "deadbeef", descriptor, true),
                source(sourceId),
                KitSignatureStatus.UNSIGNED);
    }
}
