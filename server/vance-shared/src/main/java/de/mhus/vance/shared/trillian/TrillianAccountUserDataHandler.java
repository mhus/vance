package de.mhus.vance.shared.trillian;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * A guard, not a cleaner: it stops you deleting the service account a Trillian
 * is currently running as.
 *
 * <p>The ordinary way a Trillian's account ends is that its pair ends — the
 * session lifecycle releases it, or the project delete does through
 * {@code TrillianProjectDataHandler}. Deleting the account <em>directly</em>
 * skips both and leaves the agent running with an identity that no longer
 * exists: every tool call it makes resolves to an unknown subject and is
 * denied, which reads like a permission bug and not like a shutdown.
 *
 * <p>So it blocks, and cleans up nothing — the right repair is to end the
 * Trillian, which knows how to release its own account. {@code --force} is
 * there for the case where the pair is already gone and only the account is
 * left over.
 *
 * <p>This is why {@code UserDataHandler} has a {@code deleteBlocker} and the
 * project SPI does not: "is anything still using this" is one central question
 * about a pod lease for a project, and a per-subsystem one for an account.
 */
@Component
@RequiredArgsConstructor
public class TrillianAccountUserDataHandler implements UserDataHandler {

    /** First. Its position is cosmetic — a blocker is asked before any run. */
    public static final int ORDER = 10;

    private final MongoTemplate mongoTemplate;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String id() {
        return "trillian-account-guard";
    }

    /**
     * Empty: this handler writes nothing, so claiming a collection would tell
     * the coverage probe that something is handled here when nothing is.
     */
    @Override
    public Set<String> collections() {
        return Set.of();
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public long count(String tenantId, String userName) {
        return controlProcessesRunningAs(tenantId, userName);
    }

    @Override
    public long delete(String tenantId, String userName) {
        return 0;
    }

    @Override
    public @Nullable String deleteBlocker(String tenantId, String userName) {
        long running = controlProcessesRunningAs(tenantId, userName);
        if (running == 0) {
            return null;
        }
        return running + " Trillian control process(es) run as this account — end the Trillian"
                + " instead (it releases its own account), or use --force if the pair is"
                + " already gone";
    }

    @Override
    public @Nullable String deleteNote(String tenantId, String userName) {
        long running = controlProcessesRunningAs(tenantId, userName);
        return running == 0
                ? null
                : "forced past " + running + " live Trillian control process(es) — those agents"
                        + " now authenticate as an account that does not exist";
    }

    /**
     * How many Trillian control processes name this account.
     *
     * <p>Reads the process rows rather than asking a Trillian service: the
     * account name lives in {@code engineParams}, and this has to work in a
     * process that has no brain on its classpath.
     */
    private long controlProcessesRunningAs(String tenantId, String userName) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("thinkEngine").is(TrillianProcessKeys.CONTROL_ENGINE_NAME)
                .and("engineParams." + TrillianProcessKeys.PARAM_TRILLIAN_USER_NAME)
                        .is(userName));
        return mongoTemplate.count(query, ThinkProcessDocument.class);
    }
}
