/**
 * Push share-relevant snapshots from the website into the Facelift
 * App-Group container so the iOS Share-Extension can use them.
 *
 * The bridge is `window.vanceFacelift.setShareCredentials({...})` +
 * `setProjectSnapshot([...])`, injected by the Facelift Swift plugin
 * at WebView document-start. Each call is a no-op when the website
 * is running in a plain browser ({@link isFacelift} returns false)
 * or when the bridge JS hasn't loaded yet — UI code can call these
 * helpers unconditionally.
 */
import type { ProjectSummary } from '@vance/generated';
export interface ShareCredentials {
    brainUrl: string;
    tenant: string;
    username: string;
    /** Bearer access token. */
    token: string;
    /** Refresh token — the extension uses it to re-mint expired
     *  access tokens without prompting the user. */
    refreshToken?: string;
}
/**
 * Forward the credentials minted at login (or silent refresh) into
 * the App-Group container under the current account's ID. Stored
 * for the iOS Share-Extension target to use as a bearer header.
 */
export declare function pushShareCredentials(opts: Omit<ShareCredentials, 'brainUrl'>): void;
/**
 * Forward the project list to the App-Group container. Called from
 * `useTenantProjects.reload()` after a successful `/projects` fetch.
 * Stripped to `{name, title}` — the extension's project picker only
 * needs those two fields.
 */
export declare function pushProjectSnapshot(projects: ProjectSummary[]): void;
//# sourceMappingURL=faceliftShareSetup.d.ts.map