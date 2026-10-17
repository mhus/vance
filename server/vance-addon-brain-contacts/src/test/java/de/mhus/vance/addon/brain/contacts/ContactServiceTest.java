package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Service behaviour against an in-memory document store: scopes and their
 * folding, shadowing, the write rules (own authorisation, then SYSTEM), the
 * group visibility rule, moves and pictures.
 */
class ContactServiceTest extends ContactTestWorld {

    // ── Scopes ────────────────────────────────────────────────────

    @Test
    void createDefaultsToTheUsersHubAndWritesAsSystem() {
        ContactEntry created = service.create(caller, card("Bob"), null);

        assertThat(created.scope()).isEqualTo(ContactScope.USER);
        assertThat(created.projectId()).isEqualTo(HUB);
        assertThat(docs).containsKey(HUB + "|" + ContactIds.cardPath(created.id()));
        assertThat(actors).last().extracting(WriteActor::reason).isEqualTo(WriteReason.SYSTEM);
        assertThat(actors).last().extracting(WriteActor::subject).isEqualTo(subject);
        verify(permissionService).enforce(subject, new Resource.Project(TENANT, HUB), Action.WRITE);
    }

    @Test
    void withoutUserTheDefaultIsTheProject() {
        ContactCaller headless = ContactCaller.of(TENANT, SecurityContext.SYSTEM, null, PROJECT);
        assertThat(service.defaultWriteScope(headless)).isEqualTo(ContactScope.PROJECT);
        assertThatThrownBy(() -> service.projectOf(headless, ContactScope.USER)).hasMessageContaining("without a user");
    }

    @Test
    void projectScopeInOwnHubFoldsIntoUser() {
        ContactCaller inHub = ContactCaller.of(TENANT, subject, USER, HUB);
        assertThat(service.scopeProjects(inHub)).containsOnlyKeys(ContactScope.USER, ContactScope.TENANT);
        ContactEntry created = service.create(inHub, card("Bob"), ContactScope.PROJECT);
        assertThat(created.scope()).isEqualTo(ContactScope.USER);
    }

    @Test
    void confinedCallerSeesOnlyItsOwnProject() {
        ContactCaller confined = caller.confined(PROJECT);
        assertThat(service.scopeProjects(confined)).containsOnlyKeys(ContactScope.PROJECT);
        assertThatThrownBy(() -> service.create(confined, card("Bob"), ContactScope.TENANT))
                .hasMessageContaining("out of reach");
    }

    @Test
    void tenantWriteNeedsWriteOnTenantProject() {
        assertThatThrownBy(() -> service.create(caller, card("Bob"), ContactScope.TENANT))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(docs).isEmpty();
    }

    // ── Reads ─────────────────────────────────────────────────────

    @Test
    void readsMergeAllScopesAndTheSpecificOneShadows() {
        writable.add(TENANT_PROJECT);
        ContactEntry tenantCard = service.create(caller, card("Shared Bob"), ContactScope.TENANT);
        // Same id, more specific scope: a local override.
        storeCard(PROJECT, tenantCard.id(), card("Project Bob"));
        service.create(caller, card("Private Carol"), ContactScope.USER);

        List<ContactEntry> all = service.search(caller, null, ContactSearch.Criteria.all());

        assertThat(all).extracting(e -> e.card().displayName()).containsExactly("Private Carol", "Project Bob");
        assertThat(service.get(caller, tenantCard.id(), null).scope()).isEqualTo(ContactScope.PROJECT);
        assertThat(service.get(caller, tenantCard.id(), ContactScope.TENANT)
                        .card()
                        .displayName())
                .isEqualTo("Shared Bob");
    }

    @Test
    void unreadableScopeIsLeftOutOfMergedReadsButRefusedWhenAsked() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.PROJECT);
        readable.remove(PROJECT);

        assertThat(service.search(caller, null, ContactSearch.Criteria.all())).isEmpty();
        assertThatThrownBy(() -> service.get(caller, bob.id(), ContactScope.PROJECT))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void brokenCardFilesAreReportedNotHidden() {
        String id = ContactIds.newId();
        put(HUB, ContactIds.cardPath(id), "this: [is not a card".getBytes(StandardCharsets.UTF_8), "application/yaml");

        assertThat(service.search(caller, null, ContactSearch.Criteria.all())).isEmpty();
        assertThat(service.broken(caller)).containsEntry(ContactScope.USER, List.of(ContactIds.cardPath(id)));
    }

    // ── Writes ────────────────────────────────────────────────────

    @Test
    void updateInTheWrongScopeNamesWhereTheCardLives() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.PROJECT);

        assertThatThrownBy(() -> service.update(caller, bob.id(), null, c -> c))
                .isInstanceOf(ContactNotFoundException.class)
                .hasMessageContaining("lives in project");
    }

    @Test
    void updateKeepsThePictureField() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        ContactEntry withPhoto = service.setPhoto(caller, bob.id(), null, ContactPhotoProcessorTestImages.square());
        ContactEntry renamed = service.update(
                caller,
                bob.id(),
                null,
                c -> c.toBuilder().fullName("Robert").photo(null).build());

        assertThat(withPhoto.photoPath()).isEqualTo(ContactIds.photoPath(bob.id(), "jpg"));
        assertThat(renamed.card().fullName()).isEqualTo("Robert");
        assertThat(renamed.card().photo()).isEqualTo(bob.id() + ".jpg");
        assertThat(renamed.photoPath()).isNotNull();
    }

    @Test
    void nameIsRequired() {
        assertThatThrownBy(() -> service.create(caller, ContactCard.builder().build(), null))
                .hasMessageContaining("needs a name");
    }

    @Test
    void deleteTrashesCardAndPictureAndReportsGroups() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        service.setPhoto(caller, bob.id(), null, ContactPhotoProcessorTestImages.square());
        ContactEntry group = service.create(caller, group("Friends", bob.id()), null);

        ContactService.GroupImpact impact = service.delete(caller, bob.id(), null);

        assertThat(docs.keySet()).noneMatch(k -> k.contains(bob.id()));
        assertThat(impact.groups()).extracting(ContactEntry::id).containsExactly(group.id());
    }

    // ── Groups ────────────────────────────────────────────────────

    @Test
    void groupMayHoldMembersOfItsOwnOrBroaderScope() {
        writable.add(TENANT_PROJECT);
        ContactEntry privateBob = service.create(caller, card("Bob"), ContactScope.USER);
        ContactEntry sharedCarol = service.create(caller, card("Carol"), ContactScope.TENANT);

        ContactEntry userGroup = service.create(caller, group("Mine", privateBob.id(), sharedCarol.id()), null);
        assertThat(userGroup.card().members()).containsExactly(privateBob.id(), sharedCarol.id());

        assertThatThrownBy(() -> service.create(caller, group("Shared", privateBob.id()), ContactScope.TENANT))
                .hasMessageContaining("not visible from a tenant group");
    }

    @Test
    void groupsDoNotNestAndOnlyGroupsHaveMembers() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        ContactEntry inner = service.create(caller, group("Inner", bob.id()), null);

        assertThatThrownBy(() -> service.create(caller, group("Outer", inner.id()), null))
                .hasMessageContaining("do not nest");
        assertThatThrownBy(() -> service.create(
                        caller,
                        card("Bob").toBuilder().members(List.of(bob.id())).build(),
                        null))
                .hasMessageContaining("Only a group");
    }

    @Test
    void changeMembersAddsAndRemoves() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        ContactEntry carol = service.create(caller, card("Carol"), null);
        ContactEntry team = service.create(caller, group("Team", bob.id()), null);

        ContactEntry changed = service.changeMembers(
                caller, team.id(), null, List.of(carol.id(), "mailto:x@y.org"), List.of(bob.id()));

        assertThat(changed.card().members()).containsExactly(carol.id(), "mailto:x@y.org");
        assertThat(service.resolveMembers(caller, changed))
                .containsEntry("mailto:x@y.org", null)
                .extractingByKey(carol.id())
                .extracting(e -> e.card().displayName())
                .isEqualTo("Carol");
    }

    // ── Move ──────────────────────────────────────────────────────

    @Test
    void moveKeepsIdAndPictureAndReportsStrandedGroups() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.PROJECT);
        service.setPhoto(caller, bob.id(), ContactScope.PROJECT, ContactPhotoProcessorTestImages.square());
        ContactEntry projectGroup = service.create(caller, group("Team", bob.id()), ContactScope.PROJECT);

        ContactService.MoveResult moved = service.move(caller, bob.id(), ContactScope.PROJECT, ContactScope.USER);

        assertThat(moved.entry().id()).isEqualTo(bob.id());
        assertThat(moved.entry().scope()).isEqualTo(ContactScope.USER);
        assertThat(moved.entry().photoPath()).isNotNull();
        assertThat(docs).containsKey(HUB + "|" + ContactIds.photoPath(bob.id(), "jpg"));
        assertThat(docs).doesNotContainKey(PROJECT + "|" + ContactIds.cardPath(bob.id()));
        assertThat(moved.strandedGroups()).extracting(ContactEntry::id).containsExactly(projectGroup.id());
    }

    // ── Pictures ──────────────────────────────────────────────────

    @Test
    void gravatarMissIsNoError() {
        ContactEntry bob = service.create(
                caller,
                card("Bob").toBuilder()
                        .emails(List.of(ContactValue.of("bob@x.org")))
                        .build(),
                null);
        when(gravatarClient.fetch(eq("bob@x.org"), any(Integer.class))).thenReturn(Optional.empty());

        assertThat(service.setPhotoFromGravatar(caller, bob.id(), null, null)).isEmpty();
        assertThat(service.get(caller, bob.id(), null).photoPath()).isNull();
    }

    @Test
    void gravatarNeedsAnAddress() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        assertThatThrownBy(() -> service.setPhotoFromGravatar(caller, bob.id(), null, null))
                .hasMessageContaining("no e-mail");
        verify(gravatarClient, never()).fetch(anyString(), any(Integer.class));
    }

    // ── Kind documents ────────────────────────────────────────────

    @Test
    void referenceDocumentResolvesThroughTheCascade() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        put(
                PROJECT,
                "people/bob.yaml",
                ContactCodec.serializeRef(bob.id(), null).getBytes(StandardCharsets.UTF_8),
                "application/yaml");
        put(
                PROJECT,
                "people/ghost.yaml",
                ContactCodec.serializeRef(ContactIds.newId(), null).getBytes(StandardCharsets.UTF_8),
                "application/yaml");

        ContactService.ResolvedDocument found = service.resolveDocument(caller, PROJECT, "people/bob.yaml");
        ContactService.ResolvedDocument ghost = service.resolveDocument(caller, PROJECT, "people/ghost.yaml");

        assertThat(found.card().displayName()).isEqualTo("Bob");
        assertThat(found.entry().scope()).isEqualTo(ContactScope.USER);
        assertThat(ghost.dangling()).isTrue();
        assertThat(ghost.card()).isNull();
    }
}
