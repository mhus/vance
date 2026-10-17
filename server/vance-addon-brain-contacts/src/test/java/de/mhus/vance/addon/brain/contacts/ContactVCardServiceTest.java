package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.permission.WriteActor;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** vCard import on top of the in-memory world: identity, groups, warnings, limits, the bulk path. */
class ContactVCardServiceTest extends ContactTestWorld {

    private ContactVCardService vcards;

    @BeforeEach
    void setUpVCards() {
        vcards = new ContactVCardService(service);
    }

    private static String v4(String... lines) {
        StringBuilder sb = new StringBuilder("BEGIN:VCARD\r\nVERSION:4.0\r\n");
        for (String l : lines) sb.append(l).append("\r\n");
        return sb.append("END:VCARD\r\n").toString();
    }

    private List<ContactEntry> imported() {
        return service.entries(caller, ContactScope.USER);
    }

    private ContactEntry byName(String name) {
        return imported().stream()
                .filter(e -> e.card().displayName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void reImportOfForeignUidsUpdatesInsteadOfDuplicating() {
        String vcf = v4("UID:google-1", "FN:Anna") + v4("UID:google-2", "FN:Bob");

        ContactVCardService.ImportReport first = vcards.importVcf(caller, vcf, null);
        ContactVCardService.ImportReport second = vcards.importVcf(caller, vcf.replace("FN:Bob", "FN:Robert"), null);

        assertThat(first.created()).isEqualTo(2);
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(2);
        assertThat(imported()).extracting(e -> e.card().displayName()).containsExactlyInAnyOrder("Anna", "Robert");
        assertThat(byName("Anna").card().extras()).containsEntry(ContactVCardMapper.X_SOURCE_UID, "google-1");
    }

    @Test
    void uuidUidBecomesTheCardId() {
        String id = ContactIds.newId();
        vcards.importVcf(caller, v4("UID:urn:uuid:" + id, "FN:Anna"), null);
        assertThat(service.get(caller, id, null).card().displayName()).isEqualTo("Anna");
    }

    @Test
    void groupsComeLastAndForeignMemberUidsPointAtTheImportedCards() {
        String vcf = v4("KIND:group", "FN:Team", "MEMBER:urn:uid:apple-7", "MEMBER:mailto:x@y.org")
                + v4("UID:apple-7", "FN:Anna");

        ContactVCardService.ImportReport report = vcards.importVcf(caller, vcf, null);

        ContactEntry anna = byName("Anna");
        ContactEntry team = byName("Team");
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.warnings()).isEmpty();
        assertThat(team.card().kind()).isEqualTo(ContactKind.GROUP);
        assertThat(team.card().members()).containsExactly(anna.id(), "mailto:x@y.org");
    }

    @Test
    void unknownMembersAreDroppedWithAWarning() {
        String vcf = v4("KIND:group", "FN:Team", "MEMBER:urn:uuid:" + ContactIds.newId());

        ContactVCardService.ImportReport report = vcards.importVcf(caller, vcf, null);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.warnings()).singleElement().asString().contains("1 member(s) dropped");
        assertThat(byName("Team").card().members()).isEmpty();
    }

    @Test
    void undecodablePictureCostsOnlyThePicture() {
        ContactVCardService.ImportReport report =
                vcards.importVcf(caller, v4("FN:Anna", "PHOTO:data:image/jpeg;base64,AAECAw=="), null);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.warnings()).singleElement().asString().contains("Anna: picture not taken");
        assertThat(byName("Anna").photoPath()).isNull();
    }

    @Test
    void anyPictureFailureBecomesAWarning() {
        doThrow(new IllegalStateException("storage full"))
                .when(documentService)
                .createOrReplaceBinary(
                        eq(TENANT),
                        anyString(),
                        anyString(),
                        any(byte[].class),
                        anyString(),
                        any(),
                        isNull(),
                        isNull(),
                        any(),
                        any(WriteActor.class));
        String png = Base64.getEncoder().encodeToString(ContactPhotoProcessorTestImages.square());

        ContactVCardService.ImportReport report =
                vcards.importVcf(caller, v4("FN:Anna", "PHOTO:data:image/png;base64," + png), null);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.warnings()).singleElement().asString().contains("storage full");
    }

    @Test
    void importWritesInBulkAndRebuildsTheIndexOnce() {
        StringBuilder vcf = new StringBuilder();
        for (int i = 0; i < 50; i++) vcf.append(v4("UID:src-" + i, "FN:Person " + i));
        vcf.append(v4("KIND:group", "FN:All", "MEMBER:urn:uid:src-0", "MEMBER:urn:uid:src-49"));

        ContactVCardService.ImportReport report = vcards.importVcf(caller, vcf.toString(), null);

        assertThat(report.created()).isEqualTo(51);
        assertThat(report.warnings()).isEmpty();
        // One read of the scope when the bulk opens; nothing per card.
        verify(documentService, atMost(1)).listUnderFolder(TENANT, HUB, ContactIds.FOLDER);
        assertThat(byName("All").card().members()).hasSize(2);
        assertThat(imported()).hasSize(51);
    }

    @Test
    void refusesAnOversizedFile() {
        String huge = "X".repeat(ContactVCardService.MAX_IMPORT_BYTES + 1);
        assertThatThrownBy(() -> vcards.importVcf(caller, huge, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger than");
        assertThat(docs).isEmpty();
    }

    @Test
    void refusesTooManyCards() {
        String one = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:x\r\nEND:VCARD\r\n";
        String many = one.repeat(ContactVCardService.MAX_IMPORT_CARDS + 1);
        assertThatThrownBy(() -> vcards.importVcf(caller, many, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + ContactVCardService.MAX_IMPORT_CARDS);
        assertThat(docs).isEmpty();
    }

    @Test
    void remapMembersMatchesWithAndWithoutUrnPrefix() {
        Map<String, String> ids = Map.of("abc", "id-1", "urn:uid:def", "id-2");
        assertThat(ContactVCardService.remapMembers(List.of("urn:uid:abc", "abc", "urn:uid:def", "mailto:a@b"), ids))
                .containsExactly("id-1", "id-1", "id-2", "mailto:a@b");
    }
}
