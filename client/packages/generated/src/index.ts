// Hand-maintained re-export of all DTOs generated from vance-api.
// Add a new line here when a new @GenerateTypeScript-annotated DTO appears.

export * from './access/AccessTokenRequest';
export * from './access/AccessTokenResponse';
export * from './access/RefreshTokenResponse';

export * from './documents/DocumentCreateRequest';
export * from './documents/DocumentDto';
export * from './documents/DocumentFoldersResponse';
export * from './documents/DocumentListResponse';
export * from './documents/DocumentSummary';
export * from './documents/DocumentUpdateRequest';

export * from './inbox/AnswerOutcome';
export * from './inbox/AnswerPayload';
export * from './inbox/Criticality';
export * from './inbox/InboxAnswerRequest';
export * from './inbox/InboxDelegateRequest';
export * from './inbox/InboxItemDto';
export * from './inbox/InboxItemStatus';
export * from './inbox/InboxItemType';
export * from './inbox/InboxListResponse';
export * from './inbox/InboxTagsResponse';
export * from './inbox/ResolvedBy';

export * from './projects/ProjectCreateRequest';
export * from './projects/ProjectDto';
export * from './projects/ProjectGroupCreateRequest';
export * from './projects/ProjectGroupUpdateRequest';
export * from './projects/ProjectUpdateRequest';
export * from './projects/TenantProjectsResponse';

export * from './settings/SettingDto';
export * from './settings/SettingType';
export * from './settings/SettingWriteRequest';

export * from './teams/TeamListResponse';
export * from './teams/TeamSummary';

export * from './tenant/TenantDto';
export * from './tenant/TenantUpdateRequest';

export * from './ws/ProjectGroupSummary';
export * from './ws/ProjectSummary';
