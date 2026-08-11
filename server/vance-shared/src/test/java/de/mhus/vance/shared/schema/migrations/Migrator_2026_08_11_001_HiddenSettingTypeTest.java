package de.mhus.vance.shared.schema.migrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import de.mhus.vance.shared.settings.SettingDocument;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * What this migration selects is the whole risk in it: re-typing a key to HIDDEN
 * makes it resolvable by every agent, so the query must hit the documented
 * reference-read keys and nothing else. The assertions therefore inspect the
 * generated Mongo query rather than just the call count.
 */
class Migrator_2026_08_11_001_HiddenSettingTypeTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final Migrator_2026_08_11_001_HiddenSettingType migration =
            new Migrator_2026_08_11_001_HiddenSettingType();

    /**
     * Every string-ish leaf of the filter, keys and values, without going through
     * {@code toJson()} — the query still holds the raw {@link SettingType} enum at
     * this point (Spring Data's converter maps it to a string only on the way to
     * the driver), and BSON has no codec for it.
     */
    private static List<String> leaves(Object node) {
        List<String> out = new java.util.ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Object node, List<String> out) {
        if (node instanceof Document doc) {
            doc.forEach((k, v) -> {
                out.add(k);
                collect(v, out);
            });
        } else if (node instanceof Iterable<?> it) {
            it.forEach(v -> collect(v, out));
        } else if (node != null) {
            out.add(String.valueOf(node));
        }
    }

    private Document runAndCaptureFilter() {
        when(mongo.find(any(Query.class), eq(SettingDocument.class))).thenReturn(List.of());
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(SettingDocument.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));

        migration.up(new SchemaMigrationContext(mongo, "2026-08-11_001", "test/owner"));

        ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateMulti(q.capture(), any(Update.class), eq(SettingDocument.class));
        return q.getValue().getQueryObject();
    }

    @Test
    void only_password_typed_settings_are_touched() {
        // The self-emptying filter that makes a second run a no-op (spec §3.1).
        assertThat(leaves(runAndCaptureFilter())).contains(SettingType.PASSWORD.name());
    }

    @Test
    void the_update_sets_hidden_and_nothing_else() {
        when(mongo.find(any(Query.class), eq(SettingDocument.class))).thenReturn(List.of());
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(SettingDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        migration.up(new SchemaMigrationContext(mongo, "2026-08-11_001", "test/owner"));

        ArgumentCaptor<Update> u = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateMulti(any(Query.class), u.capture(), eq(SettingDocument.class));
        Document set = u.getValue().getUpdateObject().get("$set", Document.class);
        // Only the type — the ciphertext stays as it is, no value is re-encrypted.
        assertThat(set.keySet()).containsExactly("type");
        assertThat(set.get("type")).isEqualTo(SettingType.HIDDEN);
    }

    @Test
    void the_filter_names_the_documented_reference_read_keys() {
        String flat = String.join("|", leaves(runAndCaptureFilter()));

        assertThat(flat).contains("smtp.").contains("imap.")
                .contains("credentials.jira.access_token")
                .contains("credentials.jira.api_token");
    }

    @Test
    void the_filter_does_not_reach_any_compiled_read_namespace() {
        // These are read by fixed keys in Java and must stay unreadable through a
        // reference. A pattern that swept one of them in would hand an
        // infrastructure credential to every agent — the one non-recoverable way
        // this migration could be wrong.
        String flat = String.join("|", leaves(runAndCaptureFilter()));

        assertThat(flat)
                .doesNotContain("ai.provider")
                .doesNotContain("vault.")
                .doesNotContain("office.")
                .doesNotContain("research.endpoint")
                .doesNotContain("web.serper")
                .doesNotContain("fook.upstream")
                .doesNotContain("oauth.");
    }

    @Test
    void the_jira_refresh_token_is_not_migrated() {
        // Nothing references it, so exposing it would buy nothing. The two exact
        // keys are listed precisely so a `credentials.jira.*` prefix cannot creep in.
        assertThat(Migrator_2026_08_11_001_HiddenSettingType.EXACT_KEYS)
                .doesNotContain("credentials.jira.refresh_token");
        assertThat(Migrator_2026_08_11_001_HiddenSettingType.PREFIXES)
                .noneMatch(p -> "credentials.jira.refresh_token".startsWith(p));
    }
}
