// Barrel for the issues addon's client surface.
export { default as IssuesAppKind } from './IssuesAppKind.vue';
export {
  scanIssues,
  getIssue,
  createIssue,
  patchIssue,
  archiveIssue,
  unarchiveIssue,
  addComment,
  deleteComment,
  deleteIssue,
  searchIssues,
  rebuildIssues,
} from './api';
export type { IssuesView } from './generated/issues/IssuesView';
export type { IssueView } from './generated/issues/IssueView';
export type { IssueContentView } from './generated/issues/IssueContentView';
