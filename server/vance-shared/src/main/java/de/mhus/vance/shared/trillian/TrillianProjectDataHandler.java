package de.mhus.vance.shared.trillian;

import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The service accounts a Trillian minted inside this project.
 *
 * <p>Trillian is the one engine that creates a <b>real user</b>: the user loop
 * runs headless under its own {@code _}-prefixed service account, with its own
 * grant on the project. That account is not project data — it lives in
 * {@code users}, which is tenant-scoped — so nothing else in this run would
 * touch it, and deleting the project would leave a user corpse per Trillian
 * behind. Those accumulate silently, because service accounts are hidden from
 * the ordinary user listing.
 *
 * <p>This is the same release the session lifecycle already performs when a
 * Trillian control session is closed or deleted
 * ({@code TrillianSessionLifecycleHook}); a project delete is simply another
 * way for a Trillian to end, and it needs the same ending.
 *
 * <h2>Why it sorts at {@value #ORDER}</h2>
 *
 * <p>Below every other handler, and that is load-bearing rather than tidy:
 * <b>the account name only exists on the process rows.</b> It is recorded in
 * the control process's {@code engineParams} under {@link
 * TrillianProcessKeys#PARAM_TRILLIAN_USER_NAME} — there is no back-reference
 * from the user to the project. Let the think-process handler run first and the
 * name is gone, the account is unreachable, and nothing reports a problem.
 *
 * <h2>Every generation, not the newest</h2>
 *
 * <p>All control processes of the project are read, including the closed ones
 * an archive/reactivate cycle left behind, and every distinct account name
 * found is released. An account is normally reused across a reactivate, so the
 * set is small — but a release that failed earlier shows up here as an extra
 * name, and picking "the current generation" would step right over it.
 *
 * <h2>What it does not do</h2>
 *
 * <p>The Nature's stored attributes and journal ({@code _vance/trillian/…}) are
 * <em>not</em> discarded here, although the session path does discard them.
 * They are ordinary documents in this project, so the documents handler removes
 * them a few steps later. Calling into the Nature would mean reaching for a
 * brain component from a process that may not have one — for no effect.
 *
 * <p><b>Rename does nothing</b>, and that is a real answer rather than a gap:
 * the account name carries no project name, its grant is carried over by the
 * permission-grants handler, and the attribute document travels with the
 * project like any other. There is nothing Trillian-specific left to rewrite.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianProjectDataHandler implements ProjectDataHandler {

    /**
     * Before everything. See the class comment — the account name lives only on
     * the process rows this run is about to delete.
     */
    public static final int ORDER = 50;

    private final ThinkProcessService thinkProcessService;
    private final UserService userService;

    /** Absent unless a grant-storing permission provider is loaded. */
    private final ObjectProvider<PermissionBootstrap> permissionBootstrapProvider;

    @Override
    public String id() {
        return "trillian-accounts";
    }

    /**
     * {@code users} — where the accounts are, and the only collection this
     * handler writes to. It does not own the collection, it owns these rows in
     * it; claiming it keeps the coverage probe from reporting a collection that
     * is being handled after all.
     */
    @Override
    public Set<String> collections() {
        return Set.of(UserDocument.COLLECTION);
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return accountNames(tenantId, projectId).stream()
                .filter(account -> userService.findByTenantAndName(tenantId, account).isPresent())
                .count();
    }

    @Override
    public long delete(String tenantId, String projectId) {
        long released = 0;
        for (String account : accountNames(tenantId, projectId)) {
            if (releaseAccount(tenantId, account)) {
                released++;
            }
        }
        return released;
    }

    /**
     * Grants first, then the account.
     *
     * <p>A grant keys on the user <em>name</em>, and a name comes back: service
     * accounts follow a scheme, so a leftover grant is inherited by the next
     * account minted under that name. {@code UserService.delete} does cascade
     * there through {@code UserLifecycleListener} — but only for an account that
     * still exists, and this method is also the repair path for one that does
     * not, which is exactly the case the explicit revoke covers.
     *
     * <p>Best-effort per step: a project delete must not stall because one
     * account could not be cleaned up, and the run reports what happened.
     *
     * @return whether an account was actually removed
     */
    private boolean releaseAccount(String tenantId, String account) {
        try {
            permissionBootstrapProvider.ifAvailable(pb -> pb.revokeAll(tenantId, account));
        } catch (RuntimeException e) {
            log.warn("Trillian: revoking grants of '{}' failed: {}", account, e.toString());
        }
        // Presence check rather than catching UserNotFoundException: this runs
        // as a repair path too, and an already-clean account is a success, not
        // a failure to log.
        if (userService.findByTenantAndName(tenantId, account).isEmpty()) {
            return false;
        }
        try {
            userService.delete(tenantId, account);
            log.info("Trillian: deleted service-account '{}' with its project", account);
            return true;
        } catch (RuntimeException e) {
            log.warn("Trillian: deleting account '{}' failed: {}", account, e.toString());
            return false;
        }
    }

    /**
     * Distinct service-account names recorded on the project's Trillian control
     * processes, in the order they were found.
     */
    private Set<String> accountNames(String tenantId, String projectId) {
        Set<String> accounts = new LinkedHashSet<>();
        for (ThinkProcessDocument process : thinkProcessService.findAllByProjectAndEngine(
                tenantId, projectId, TrillianProcessKeys.CONTROL_ENGINE_NAME)) {
            String account = paramString(process, TrillianProcessKeys.PARAM_TRILLIAN_USER_NAME);
            if (account != null && !account.isBlank()) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    private static @Nullable String paramString(ThinkProcessDocument process, String key) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) {
            return null;
        }
        Object value = params.get(key);
        return value == null ? null : value.toString();
    }
}
