package de.mhus.vance.shared.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Members change through one atomic update each — {@code $addToSet} and
 * {@code $pull} — so two concurrent joins cannot overwrite each other the way
 * a read-modify-write of the whole list would.
 */
class TeamServiceMembersTest {

    private MongoTemplate mongoTemplate;
    private TeamRepository repository;
    private TeamQuotaService quota;
    private TeamService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        repository = mock(TeamRepository.class);
        quota = mock(TeamQuotaService.class);
        ObjectProvider<TeamQuotaService> quotaProvider = mock(ObjectProvider.class);
        when(quotaProvider.getObject()).thenReturn(quota);
        ObjectProvider<TeamLifecycleListener> listeners = mock(ObjectProvider.class);
        when(listeners.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.empty());
        service = new TeamService(repository, mongoTemplate, listeners, quotaProvider);
    }

    private void result(long matched, long modified) {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TeamDocument.class)))
                .thenReturn(UpdateResult.acknowledged(matched, modified, null));
    }

    @Test
    void addMember_usesAddToSet() {
        result(1, 1);

        assertThat(service.addMember("acme", "infra", "bob")).isTrue();

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(TeamDocument.class));
        assertThat(query.getValue().getQueryObject())
                .containsEntry("tenantId", "acme")
                .containsEntry("name", "infra");
        assertThat(update.getValue().getUpdateObject().get("$addToSet", Document.class))
                .containsEntry("members", "bob");
    }

    @Test
    void addMember_alreadyMemberIsNoChange() {
        result(1, 0);

        assertThat(service.addMember("acme", "infra", "bob")).isFalse();
    }

    @Test
    void removeMember_usesPull() {
        result(1, 1);

        assertThat(service.removeMember("acme", "infra", "bob")).isTrue();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(TeamDocument.class));
        assertThat(update.getValue().getUpdateObject().get("$pull", Document.class))
                .containsEntry("members", "bob");
    }

    @Test
    void unknownTeam_isNotFound() {
        result(0, 0);

        assertThatThrownBy(() -> service.removeMember("acme", "ghost", "bob"))
                .isInstanceOf(TeamService.TeamNotFoundException.class);
    }

    @Test
    void update_setsOnlyTheGivenFields_neverTheMembers() {
        TeamDocument after = TeamDocument.builder()
                .tenantId("acme")
                .name("infra")
                .members(List.of("alice", "bob"))
                .enabled(true)
                .build();
        when(mongoTemplate.findAndModify(
                        any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TeamDocument.class)))
                .thenReturn(after);
        when(repository.findByTenantIdAndName("acme", "infra")).thenReturn(java.util.Optional.of(after));

        assertThat(service.update("acme", "infra", null, true, null)).isSameAs(after);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate)
                .findAndModify(
                        any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(TeamDocument.class));
        Document set = update.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(set)
                .containsEntry("enabled", true)
                .doesNotContainKey("members")
                .doesNotContainKey("title");
    }

    @Test
    void update_unknownTeam_isNotFound() {
        assertThatThrownBy(() -> service.update("acme", "ghost", "T", null, null))
                .isInstanceOf(TeamService.TeamNotFoundException.class);
    }

    // ─── Quota: enforced here, bypassed only by the explicit overload ───────

    @Test
    void create_atTheLimit_isRefusedBeforeAnyWrite() {
        doThrow(new TeamQuotaService.TeamQuotaExceededException(3, 3))
                .when(quota)
                .checkOneMore("acme");

        assertThatThrownBy(() -> service.create("acme", "ops", null, null))
                .isInstanceOf(TeamQuotaService.TeamQuotaExceededException.class);
        verify(repository, never()).save(any(TeamDocument.class));
    }

    @Test
    void create_withoutEnforcement_skipsTheQuota() {
        when(repository.save(any(TeamDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create("acme", "ops", null, null, false);

        verify(quota, never()).checkOneMore(any());
    }

    @Test
    void enabling_checksTheEnabledChange_beforeWriting() {
        TeamDocument ops = TeamDocument.builder()
                .tenantId("acme")
                .name("ops")
                .enabled(false)
                .build();
        when(repository.findByTenantIdAndName("acme", "ops")).thenReturn(java.util.Optional.of(ops));
        doThrow(new TeamQuotaService.TeamQuotaExceededException(3, 3))
                .when(quota)
                .checkEnabledChange("acme", ops, true);

        assertThatThrownBy(() -> service.update("acme", "ops", null, true, null))
                .isInstanceOf(TeamQuotaService.TeamQuotaExceededException.class);
        verify(mongoTemplate, never())
                .findAndModify(
                        any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TeamDocument.class));
    }

    @Test
    void enabling_withoutEnforcement_skipsTheQuota() {
        when(mongoTemplate.findAndModify(
                        any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TeamDocument.class)))
                .thenReturn(TeamDocument.builder().tenantId("acme").name("ops").build());

        service.update("acme", "ops", null, true, null, false);

        verify(quota, never()).checkEnabledChange(any(), any(), anyBoolean());
    }
}
