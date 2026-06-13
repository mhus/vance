import { brainBaseUrl, isFacelift } from '@vance/shared';
function bridge() {
    if (!isFacelift())
        return null;
    const b = window
        .vanceFacelift;
    return b ?? null;
}
/**
 * Forward the credentials minted at login (or silent refresh) into
 * the App-Group container under the current account's ID. Stored
 * for the iOS Share-Extension target to use as a bearer header.
 */
export function pushShareCredentials(opts) {
    const b = bridge();
    if (b?.setShareCredentials === undefined)
        return;
    b.setShareCredentials({
        brainUrl: brainBaseUrl(),
        tenant: opts.tenant,
        username: opts.username,
        token: opts.token,
        refreshToken: opts.refreshToken,
    });
}
/**
 * Forward the project list to the App-Group container. Called from
 * `useTenantProjects.reload()` after a successful `/projects` fetch.
 * Stripped to `{name, title}` — the extension's project picker only
 * needs those two fields.
 */
export function pushProjectSnapshot(projects) {
    const b = bridge();
    if (b?.setProjectSnapshot === undefined)
        return;
    b.setProjectSnapshot(projects.map((p) => ({ name: p.name, title: p.title })));
}
//# sourceMappingURL=faceliftShareSetup.js.map