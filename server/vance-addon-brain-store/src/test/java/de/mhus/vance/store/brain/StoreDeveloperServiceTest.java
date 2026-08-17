package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.KitWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Publishing a project — spec: {@code planning/kit-store.md} §3 S15.
 */
@ExtendWith(MockitoExtension.class)
class StoreDeveloperServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "widgets-kit";
    private static final String TOKEN = "vst_link";

    @Mock private KitService kitService;
    @Mock private KitWorkspace workspace;
    @Mock private StoreClient client;

    @InjectMocks private StoreDeveloperService service;

    @TempDir Path staging;

    @Test
    void publish_exportsIntoAFolderRatherThanAGitRemote() throws IOException {
        // The export already knows how to build a kit tree. Teaching a
        // second path to do it would mean two of them, and one would rot.
        givenStaging("kit.yaml", "name: widgets");

        service.publish(TENANT, PROJECT, "marvin", source(), TOKEN,
                "acme", "widgets", "1.0.0", null);

        org.mockito.ArgumentCaptor<KitExportRequestDto> request =
                org.mockito.ArgumentCaptor.forClass(KitExportRequestDto.class);
        verify(kitService).export(eq(TENANT), request.capture(), eq("marvin"));
        assertThat(request.getValue().getProjectId()).isEqualTo(PROJECT);
        assertThat(request.getValue().getUrl()).startsWith("file:");
    }

    @Test
    void publish_uploadsWhatWasExported() throws IOException {
        givenStaging("kit.yaml", "name: widgets");
        Files.createDirectories(staging.resolve("documents"));
        Files.writeString(staging.resolve("documents/skill.md"), "# skill");

        // Read while the call is still running: the archive is a temp file
        // and the service deletes it on the way out, which is what it
        // should do. Capturing the path and looking afterwards would be
        // testing the cleanup by tripping over it.
        List<String> uploaded = new ArrayList<>();
        when(client.uploadRelease(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    uploaded.addAll(entriesOf(invocation.getArgument(5)));
                    return null;
                });

        service.publish(TENANT, PROJECT, "marvin", source(), TOKEN,
                "acme", "widgets", "1.0.0", null);

        verify(client).uploadRelease(any(), eq(TOKEN), eq("acme"), eq("widgets"),
                eq("1.0.0"), any());
        // Paths relative and no directory entries: the store refuses an
        // archive that could write outside its release directory, and
        // producing one here would only turn a clear upload into a
        // rejected one.
        assertThat(uploaded).containsExactlyInAnyOrder("kit.yaml", "documents/skill.md");
    }

    @Test
    void publish_releasesTheStagingDirectory() throws IOException {
        givenStaging("kit.yaml", "name: widgets");

        service.publish(TENANT, PROJECT, "marvin", source(), TOKEN,
                "acme", "widgets", "1.0.0", null);

        verify(workspace).remove(staging);
    }

    @Test
    void publish_releasesTheStagingDirectoryWhenTheUploadFails() throws IOException {
        givenStaging("kit.yaml", "name: widgets");
        when(client.uploadRelease(any(), any(), any(), any(), any(), any()))
                .thenThrow(new de.mhus.vance.shared.kit.KitException("the store said no"));

        try {
            service.publish(TENANT, PROJECT, "marvin", source(), TOKEN,
                    "acme", "widgets", "1.0.0", null);
        } catch (RuntimeException expected) {
            // the point of the test is what happens afterwards
        }

        verify(workspace).remove(staging);
    }

    @Test
    void publish_packsTheKitTreeAndNotTheStagingRoot() throws IOException {
        // The export writes into the sub-path its manifest names — a kit
        // repository holds several kits. An upload is a release, and the
        // store expects kit.yaml at the top. Packing the staging root
        // shipped acmelabs/widgets/kit.yaml and delivery refused the
        // download: a well-formed archive about the wrong thing.
        when(workspace.allocate("store-publish")).thenReturn(staging);
        Files.createDirectories(staging.resolve("acmelabs/widgets/documents"));
        Files.writeString(staging.resolve("acmelabs/widgets/kit.yaml"), "name: widgets");
        Files.writeString(staging.resolve("acmelabs/widgets/documents/skill.md"), "# skill");

        List<String> uploaded = new ArrayList<>();
        when(client.uploadRelease(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    uploaded.addAll(entriesOf(invocation.getArgument(5)));
                    return null;
                });

        service.publish(TENANT, PROJECT, "marvin", source(), TOKEN,
                "acme", "widgets", "1.0.0", null);

        assertThat(uploaded).containsExactlyInAnyOrder("kit.yaml", "documents/skill.md");
    }

    @Test
    void publish_withoutAKitYaml_saysSo() throws IOException {
        // Better a named failure here than a rejected download later, where
        // the message is about a file nobody meant to leave out.
        when(workspace.allocate("store-publish")).thenReturn(staging);
        Files.writeString(staging.resolve("README.md"), "nothing to publish");

        assertThatThrownBy(() -> service.publish(TENANT, PROJECT, "marvin", source(), TOKEN,
                "acme", "widgets", "1.0.0", null))
                .hasMessageContaining("no kit.yaml");
    }

    private void givenStaging(String file, String content) throws IOException {
        when(workspace.allocate("store-publish")).thenReturn(staging);
        Files.writeString(staging.resolve(file), content);
    }

    private static List<String> entriesOf(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }

    private static KitSourceDto source() {
        return KitSourceDto.builder()
                .id("vancetope-library")
                .type(KitSourceType.LIBRARY)
                .url("https://library.example.com")
                .build();
    }
}
