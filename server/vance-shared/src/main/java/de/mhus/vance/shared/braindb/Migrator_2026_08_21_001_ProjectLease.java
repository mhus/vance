package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Retires the pre-lease ownership fields on {@code projects}.
 *
 * <p>Ownership moved from "a node name written as a fact" to a self-expiring
 * lease keyed on {@code homePodId} (see
 * {@code planning/project-ownership-lease.md} §3). A document without
 * {@code homePodId} is therefore <em>already</em> unowned — correctness needs
 * no migration at all.
 *
 * <p>What does need cleaning is the display: every project carries a
 * {@code homeNode} naming a pod that stopped existing at some point, plus a
 * {@code claimedAt} from that era, and both are surfaced in the admin REST
 * response, the Insights cluster tab and {@code anus project show}. Left in
 * place they read as "owned by a pod you cannot find" forever, which is the
 * exact confusion the lease exists to end.
 *
 * <p>Self-emptying filter: only rows that have no {@code homePodId} but still
 * carry one of the two legacy values match, and the update removes those, so a
 * replay after a stolen lease matches nothing.
 */
public final class Migrator_2026_08_21_001_ProjectLease implements SchemaMigration {

    @Override
    public void up(SchemaMigrationContext context) {
        Query query = new Query(Criteria.where("homePodId").exists(false)
                .orOperator(
                        Criteria.where("homeNode").ne(null),
                        Criteria.where("claimedAt").ne(null)));
        Update update = new Update().unset("homeNode").unset("claimedAt");
        UpdateResult result = context.mongoTemplate()
                .updateMulti(query, update, "projects");
        // The service logs the migration itself; this line is the payload count,
        // which is the only thing it cannot know.
        System.getLogger(Migrator_2026_08_21_001_ProjectLease.class.getName())
                .log(System.Logger.Level.INFO,
                        "cleared legacy home-node claim on " + result.getModifiedCount()
                                + " project(s)");
    }
}
