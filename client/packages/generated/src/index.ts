// Hand-maintained re-export of all DTOs generated from vance-api.
// Add a new line here when a new @GenerateTypeScript-annotated DTO appears.

export * from './common/AccentColor';

export * from './access/AccessTokenRequest';
export * from './access/AccessTokenResponse';
export * from './access/RefreshTokenResponse';
export * from './access/WebUiSessionData';

export * from './applications/ApplicationEntryDto';
export * from './applications/ApplicationListResponse';
export * from './applications/ApplicationTargetDto';
export * from './applications/ApplicationTargetsResponse';

export * from './addon/AddonDto';
export * from './addon/AddonInsightDto';
export * from './addon/AddonProfileTabDto';
export * from './addon/ChecksumStatus';

export * from './attachment/AttachmentRef';

export * from './command/EngineCommandOutcome';
export * from './command/ProcessCommandRequest';
export * from './command/ProcessCommandResponse';

export * from './geocode/GeocodeResult';

export * from './profile/ProfileDto';
export * from './profile/ProfilePasswordRequest';
export * from './profile/ProfileSettingWriteRequest';
export * from './profile/ProfileUpdateRequest';

export * from './scripts/ScriptCreateRequest';
export * from './scripts/ScriptDeepValidateResponse';
export * from './scripts/ScriptDeepWarning';
export * from './scripts/ScriptExecuteRequest';
export * from './scripts/ScriptExecuteResponse';
export * from './scripts/ScriptExecutionEventData';
export * from './scripts/ScriptExecutionStatus';
export * from './scripts/ScriptExecutionSubscribeRequest';
export * from './scripts/ScriptGenerateRequest';
export * from './scripts/ScriptGenerateResponse';
export * from './scripts/ScriptGenerationResult';
export * from './scripts/ScriptValidateError';
export * from './scripts/ScriptValidateRequest';
export * from './scripts/ScriptValidateResponse';

export * from './python/PythonExecuteRequest';
export * from './python/PythonExecuteResponse';
export * from './python/PythonExecutionStatus';

export * from './eddie/ChannelMode';
export * from './eddie/SwitchToNotification';

export * from './documents/DocumentArchiveCreateResponse';
export * from './documents/DocumentArchiveDto';
export * from './documents/DocumentArchiveListResponse';
export * from './documents/DocumentArchiveSummary';
export * from './documents/DocumentCreateRequest';
export * from './documents/DocumentDto';
export * from './documents/DocumentExportRequest';
export * from './documents/DocumentFolderListResponse';
export * from './documents/DocumentCopyChunkRequest';
export * from './documents/DocumentCopyChunkResponse';
export * from './documents/DocumentFoldersResponse';
export * from './documents/DocumentInvalidateNotification';
export * from './documents/DocumentKindsResponse';
export * from './documents/DocumentListResponse';
export * from './documents/DocumentMoveChunkRequest';
export * from './documents/DocumentMoveChunkResponse';
export * from './documents/DocumentNoteCreateRequest';
export * from './documents/DocumentNoteDto';
export * from './documents/DocumentNoteUpdateRequest';
export * from './documents/DocumentRenameChunkRequest';
export * from './documents/DocumentRenameChunkResponse';
export * from './documents/DocumentSearchItem';
export * from './documents/DocumentSearchResponse';
export * from './documents/DocumentSummary';
export * from './documents/DocumentSummaryRequest';
export * from './documents/DocumentTrashChunkRequest';
export * from './documents/DocumentTrashChunkResponse';
export * from './documents/DocumentUnpackResponse';
export * from './documents/DocumentUpdateRequest';
export * from './documents/MountAccess';
export * from './documents/MountDto';
export * from './documents/MountListResponse';
export * from './documents/MountSearchOutcome';
export * from './documents/WriterRole';

export * from './oauth/OAuthProviderAdminDto';
export * from './oauth/OAuthProviderListEntry';
export * from './oauth/OAuthProviderWriteRequest';

export * from './chat/ChatMessageAppendedData';
export * from './chat/ChatMessageChunkData';
export * from './chat/ChatMessageDto';
export * from './chat/ChatRole';
export * from './chat/SessionCropRequest';

export * from './inbox/AnswerOutcome';
export * from './inbox/EffectDescription';
export * from './inbox/EffectFact';
export * from './inbox/AnswerPayload';
export * from './inbox/Criticality';
export * from './inbox/InboxAnswerRequest';
export * from './inbox/InboxCountResponse';
export * from './inbox/InboxDelegateRequest';
export * from './inbox/InboxFollowRequest';
export * from './inbox/InboxInviteRequest';
export * from './inbox/InboxParticipantRemoveRequest';
export * from './inbox/InboxListResponse';
export * from './inbox/InboxMessagePostRequest';
export * from './inbox/InboxReactRequest';
export * from './inbox/InboxReadRequest';
export * from './inbox/InboxTagsResponse';
export * from './inbox/MaximegalonDocumentRef';
export * from './inbox/MaximegalonDto';
export * from './inbox/MaximegalonMessageDto';
export * from './inbox/MaximegalonReactionDto';
export * from './inbox/MaximegalonStatus';
export * from './inbox/MaximegalonType';
export * from './inbox/ResolvedBy';


export * from './kit/InheritArtefactsDto';
export * from './kit/KitArtefactDto';
export * from './kit/KitArtefactsDto';
export * from './kit/KitConfigDto';
export * from './kit/KitDescriptorDto';
export * from './kit/KitExportRequestDto';
export * from './kit/KitImportMode';
export * from './kit/KitImportRequestDto';
export * from './kit/KitInheritDto';
export * from './kit/KitInstalledRecordDto';
export * from './kit/KitLibraryEntryDto';
export * from './kit/KitManifestDto';
export * from './kit/KitMetadataDto';
export * from './kit/KitOperationResultDto';
export * from './kit/KitOriginDto';
export * from './kit/KitPolicyAction';
export * from './kit/KitProvisioningAuthority';
export * from './kit/KitPolicyDto';
export * from './kit/KitPolicyRuleDto';
export * from './kit/KitSignatureDto';
export * from './kit/KitSignaturePolicy';
export * from './kit/KitSignatureStatus';
export * from './kit/KitSourceDto';
export * from './kit/KitSourceType';
export * from './kit/KitSourcesDto';
export * from './kit/ProjectKitEntry';
export * from './kit/ProjectKitsCatalogDto';
export * from './kit/ProjectKitsScanRequestDto';
export * from './kit/ToolTemplateAppliedStateDto';
export * from './kit/ToolTemplateApplyRequestDto';
export * from './kit/ToolTemplateApplyResultDto';
export * from './kit/ToolTemplateChoiceDto';
export * from './kit/ToolTemplateCatalogDto';
export * from './kit/ToolTemplateCatalogEntry';
export * from './kit/ToolTemplateDescriptorDto';
export * from './kit/ToolTemplateInputDto';
export * from './kit/ToolTemplatePostInstallDto';
export * from './kit/ToolTemplatesScanRequestDto';

export * from './form/BindsToDto';
export * from './form/FormChoiceDto';
export * from './form/FormFieldDto';

export * from './followup/FollowUpRequestDto';
export * from './followup/FollowUpResponseDto';
export * from './followup/FollowUpSuggestionDto';

export * from './fook/FookSubmissionRequestDto';
export * from './fook/FookSubmissionResponseDto';

export * from './settingform/AppliedSettingDto';
export * from './settingform/ComputedSettingDto';
export * from './settingform/SettingFormApplyRequestDto';
export * from './settingform/SettingFormApplyResponseDto';
export * from './settingform/SettingFormDto';
export * from './settingform/SettingFormListResponseDto';
export * from './settingform/SettingFormSummaryDto';

export * from './wizard/WizardDto';
export * from './wizard/WizardListResponseDto';
export * from './wizard/WizardRenderRequestDto';
export * from './wizard/WizardRenderResponseDto';
export * from './wizard/WizardSummaryDto';

export * from './execution/ExecEvent';
export * from './execution/ExecListSnapshot';
export * from './execution/ExecutionInsightsDto';
export * from './execution/ExecutionTailDto';

export * from './insights/ActiveSkillInsightsDto';
export * from './insights/BrainPodInsightsDto';
export * from './insights/BrainPodProjectInsightsDto';
export * from './insights/CacheStatsDto';
export * from './insights/ChatMessageInsightsDto';
export * from './insights/ClusterInsightsDto';
export * from './insights/EffectiveRecipeDto';
export * from './insights/EffectiveToolDto';
export * from './insights/MarvinNodeInsightsDto';
export * from './insights/MemoryInsightsDto';
export * from './insights/PendingMessageInsightsDto';
export * from './insights/PrakRunInsightsDto';
export * from './insights/SessionClientToolsDto';
export * from './insights/SessionInsightsDto';
export * from './insights/ThinkProcessInsightsDto';
export * from './insights/UsageBucketDto';
export * from './insights/UsageReportDto';
export * from './insights/ToolUsageEntryInsightsDto';
export * from './insights/ToolUsageRoleInsightsDto';
export * from './insights/FacetInsightsDto';
export * from './insights/FacetValueInsightsDto';
export * from './insights/ZarniwoopInsightsDto';

export * from './progress/MetricsPayload';
export * from './progress/PlanNode';
export * from './progress/PlanPayload';
export * from './progress/ProcessProgressNotification';
export * from './progress/ProgressKind';
export * from './progress/StatusPayload';
export * from './progress/StatusTag';
export * from './progress/UsageDelta';

export * from './llmtrace/LlmTraceDto';
export * from './llmtrace/LlmTraceListResponse';

export * from './projects/ProjectCreateRequest';
export * from './projects/ProjectDto';
export * from './projects/ProjectGroupCreateRequest';
export * from './projects/ProjectGroupUpdateRequest';
export * from './projects/ProjectUpdateRequest';
export * from './projects/TenantProjectsResponse';
export * from './projects/WorkspaceNodeType';
export * from './projects/WorkspaceTreeNodeDto';

export * from './skills/ActiveSkillRefDto';
export * from './skills/ProcessSkillCommand';
export * from './skills/ProcessSkillRequest';
export * from './skills/ProcessSkillResponse';
export * from './skills/SkillReferenceDocDto';
export * from './skills/SkillReferenceDocLoadMode';
export * from './skills/SkillScope';
export * from './skills/SkillSummaryDto';
export * from './skills/SkillTriggerDto';
export * from './skills/SkillTriggerType';

export * from './thinkprocess/ActiveAppContext';
export * from './thinkprocess/ActiveInboxContext';
export * from './thinkprocess/BootstrappedProcess';
export * from './thinkprocess/BoundDocSelection';
export * from './thinkprocess/IdeContext';
export * from './thinkprocess/IdeFileRange';
export * from './thinkprocess/PlanProposedNotification';
export * from './thinkprocess/ProcessCountsNotification';
export * from './thinkprocess/ProcessListRequest';
export * from './thinkprocess/ProcessListResponse';
export * from './thinkprocess/ProcessMessagesRequest';
export * from './thinkprocess/ProcessMessagesResponse';
export * from './thinkprocess/ProcessMode';
export * from './thinkprocess/ProcessModeChangedNotification';
export * from './thinkprocess/ProcessPauseRequest';
export * from './thinkprocess/ProcessPauseResponse';
export * from './thinkprocess/ProcessSpec';
export * from './thinkprocess/ProcessSteerRequest';
export * from './thinkprocess/ProcessSteerResponse';
export * from './thinkprocess/ProcessSummary';
export * from './thinkprocess/PromptMode';
export * from './thinkprocess/SessionBootstrapRequest';
export * from './thinkprocess/SessionBootstrapResponse';
export * from './thinkprocess/ThinkProcessStatus';
export * from './thinkprocess/TodoItem';
export * from './thinkprocess/TodoStatus';
export * from './thinkprocess/TodosUpdatedNotification';

export * from './scheduler/OverlapPolicy';
export * from './scheduler/SchedulerDto';
export * from './scheduler/SchedulerSaveRequest';
export * from './scheduler/SchedulerSource';
export * from './scheduler/SchedulerSummary';

export * from './events/EventDto';
export * from './events/EventSource';
export * from './events/EventSummary';
export * from './events/EventTriggerResponse';

export * from './runs/RunAction';
export * from './runs/RunChildDto';
export * from './runs/RunDetailDto';
export * from './runs/RunLinkDto';
export * from './runs/RunStatus';
export * from './runs/RunStepDto';
export * from './runs/RunSummaryDto';
export * from './magrathea/MagratheaParameterDto';
export * from './magrathea/MagratheaProcessDto';
export * from './magrathea/MagratheaRunStatus';
export * from './magrathea/MagratheaWorkflowDto';
export * from './magrathea/MagratheaWorkflowSource';
export * from './magrathea/MagratheaWorkflowSummary';

export * from './notification/NotificationDto';
export * from './notification/NotificationSeverity';

export * from './recipe/RecipeListedDto';


export * from './ursahooks/UrsaHookDto';
export * from './ursahooks/UrsaHookEventName';
export * from './ursahooks/UrsaHookSaveRequest';
export * from './ursahooks/UrsaHookSource';
export * from './ursahooks/UrsaHookScriptSpec';
export * from './ursahooks/UrsaHookSummary';

export * from './server-tools/ServerToolDto';
export * from './server-tools/ServerToolWriteRequest';
export * from './server-tools/ToolTypeDto';

export * from './tools/ClientToolInvokeRequest';
export * from './tools/ClientToolInvokeResponse';
export * from './tools/ClientToolRegisterRequest';
export * from './tools/DaemonRegisterRequest';
export * from './tools/ToolSpec';

export * from './toolhealth/ToolErrorCode';
export * from './toolhealth/ToolHealthClassification';
export * from './toolhealth/ToolHealthCooldownDto';
export * from './toolhealth/ToolHealthEntryDto';
export * from './toolhealth/ToolHealthScope';
export * from './toolhealth/ToolHealthStatus';
export * from './toolhealth/ToolSafety';

export * from './session/DisconnectPolicy';
export * from './session/IdlePolicy';
export * from './session/SessionGroupAssignRequest';
export * from './session/SessionGroupCreateRequest';
export * from './session/SessionCompactResponse';
export * from './session/SessionDuplicateRequest';
export * from './session/SessionDuplicateResponse';
export * from './session/SessionMoveRequest';
export * from './session/SessionMoveResponse';
export * from './session/SessionGroupDto';
export * from './session/SessionGroupRenameRequest';
export * from './session/SessionGroupReorderRequest';
export * from './session/SessionLifecycleConfig';
export * from './session/SessionMetadataDto';
export * from './session/SessionMetadataPatchRequest';
export * from './session/SessionParticipantDto';
export * from './session/SessionRosterData';
export * from './session/SessionSearchHitDto';
export * from './session/SessionSearchScope';
export * from './session/SessionStatus';
export * from './session/SessionSummaryRichDto';
export * from './session/SuspendCause';
export * from './session/SuspendPolicy';

export * from './settings/SettingDto';
export * from './settings/SettingType';
export * from './settings/SettingWriteRequest';

export * from './teams/TeamListResponse';
export * from './teams/TeamSummary';

export * from './tenant/TenantDto';
export * from './tenant/TenantUpdateRequest';

export * from './uistate/SessionGroupsUiStateDto';
export * from './uistate/SidebarUiStateDto';

export * from './users/TeamCreateRequest';
export * from './users/TeamDto';
export * from './users/TeamUpdateRequest';
export * from './users/UserCreateRequest';
export * from './users/UserDto';
export * from './users/UserPasswordRequest';
export * from './users/UserUpdateRequest';

export * from './web/LinkPreviewDto';

export * from './ws/ClientAgentUploadRequest';
export * from './ws/ClientContext';
export * from './ws/DocumentChangedNotification';
export * from './ws/DocumentNoteChangedNotification';
export * from './ws/DocumentPresenceNotification';
export * from './ws/DocumentPrefixSubscribeRequest';
export * from './ws/DocumentSubscribeRequest';
export * from './ws/DocumentViewer';
export * from './ws/ErrorData';
export * from './ws/LiveEnvelope';
export * from './ws/PointerSubscribeRequest';
export * from './ws/PointerMoveRequest';
export * from './ws/PointerNotification';
export * from './ws/PointerLeaveNotification';
export * from './ws/SignalFrame';
export * from './ws/SignalSubscribeRequest';
export * from './ws/RemoteAttachRequest';
export * from './ws/RemoteClientAnnounce';
export * from './ws/RemoteClientInfo';
export * from './ws/RemoteClientPrompt';
export * from './ws/RemoteClientPromptOption';
export * from './ws/RemoteClientRoster';
export * from './ws/RemoteClientState';
export * from './ws/RemoteInputRequest';
export * from './ws/RemoteInterruptRequest';
export * from './ws/RemoteOutputBatch';
export * from './ws/RemoteOutputLine';
export * from './ws/ProjectGroupSummary';
export * from './ws/ProjectListRequest';
export * from './ws/ProjectListResponse';
export * from './ws/PingData';
export * from './ws/PongData';
export * from './ws/ProjectSummary';
export * from './ws/ServerInfo';
export * from './ws/SessionCreateRequest';
export * from './ws/SessionCreateResponse';
export * from './ws/SessionListRequest';
export * from './ws/SessionListResponse';
export * from './ws/SessionResumeRequest';
export * from './ws/SessionResumeResponse';
export * from './ws/SessionSummary';
export * from './ws/WelcomeData';
export * from './template/TemplateSummaryDto';
export * from './template/TemplateDto';
export * from './template/TemplateListResponseDto';
export * from './template/TemplateApplyRequestDto';
export * from './template/TemplateApplyResponseDto';
export * from './milliways/ShareHandlerDto';
export * from './milliways/ShareSubjectDto';
export * from './milliways/ShareContextRequest';
export * from './milliways/ShareFormDto';
export * from './milliways/ShareSubmitRequest';
export * from './milliways/ShareResultDto';
export * from './starred/StarredItemDto';
export * from './starred/StarredRequest';
export * from './starred/StarredReconcileEntryDto';
export * from './starred/StarredReconcileDto';
export * from './megadodo/MegadodoPhase';
export * from './megadodo/MegadodoSeverity';
export * from './megadodo/MegadodoRefType';
export * from './megadodo/MegadodoEventDto';
export * from './megadodo/MegadodoPageDto';
