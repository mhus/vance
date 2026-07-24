import { brainFetch } from '@vance/shared';
import type { IssuesView } from './generated/issues/IssuesView';
import type { IssueContentView } from './generated/issues/IssueContentView';
import type { IssueCreateRequest } from './generated/issues/IssueCreateRequest';
import type { IssuePatchRequest } from './generated/issues/IssuePatchRequest';
import type { IssueCommentRequest } from './generated/issues/IssueCommentRequest';
import type { IssuesSearchResponse } from './generated/issues/IssuesSearchResponse';
import type { IssuesRebuildResponse } from './generated/issues/IssuesRebuildResponse';

function qs(params: Record<string, string | number | boolean | undefined>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === '') continue;
    u.set(k, String(v));
  }
  return u.toString();
}

export async function scanIssues(
  projectId: string,
  folder: string,
  state?: string,
  archived?: boolean,
): Promise<IssuesView> {
  return brainFetch<IssuesView>('GET', `addon/issues/scan?${qs({ projectId, folder, state, archived })}`);
}

export async function getIssue(projectId: string, path: string): Promise<IssueContentView> {
  return brainFetch<IssueContentView>('GET', `addon/issues/issue?${qs({ projectId, path })}`);
}

export async function createIssue(
  projectId: string,
  folder: string,
  request: IssueCreateRequest,
): Promise<IssueContentView> {
  return brainFetch<IssueContentView>('POST', `addon/issues/issue?${qs({ projectId, folder })}`, { body: request });
}

export async function patchIssue(
  projectId: string,
  path: string,
  request: IssuePatchRequest,
): Promise<IssueContentView> {
  return brainFetch<IssueContentView>('PATCH', `addon/issues/issue?${qs({ projectId, path })}`, { body: request });
}

export async function archiveIssue(projectId: string, folder: string, path: string): Promise<IssueContentView> {
  return brainFetch<IssueContentView>('POST', `addon/issues/issue/archive?${qs({ projectId, folder, path })}`);
}

export async function unarchiveIssue(projectId: string, folder: string, path: string): Promise<IssueContentView> {
  return brainFetch<IssueContentView>('POST', `addon/issues/issue/unarchive?${qs({ projectId, folder, path })}`);
}

export async function addComment(
  projectId: string,
  path: string,
  request: IssueCommentRequest,
): Promise<IssueContentView> {
  return brainFetch<IssueContentView>('POST', `addon/issues/issue/comment?${qs({ projectId, path })}`, { body: request });
}

export async function deleteComment(projectId: string, path: string, commentId: string): Promise<IssueContentView> {
  return brainFetch<IssueContentView>('DELETE', `addon/issues/issue/comment?${qs({ projectId, path, commentId })}`);
}

export async function deleteIssue(projectId: string, path: string): Promise<void> {
  await brainFetch<unknown>('DELETE', `addon/issues/issue?${qs({ projectId, path })}`);
}

export async function searchIssues(
  projectId: string,
  folder: string,
  query: string,
  label?: string,
): Promise<IssuesSearchResponse> {
  return brainFetch<IssuesSearchResponse>('GET', `addon/issues/search?${qs({ projectId, folder, q: query, label })}`);
}

export async function rebuildIssues(projectId: string, folder: string): Promise<IssuesRebuildResponse> {
  return brainFetch<IssuesRebuildResponse>('POST', `addon/issues/rebuild?${qs({ projectId, folder })}`);
}
