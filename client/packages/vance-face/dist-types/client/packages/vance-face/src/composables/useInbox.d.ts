import { type Ref } from 'vue';
import { AnswerOutcome, InboxItemStatus, type InboxItemDto } from '@vance/generated';
export type AssignedToFilter = {
    kind: 'self';
} | {
    kind: 'team';
    teamName: string;
} | {
    kind: 'user';
    userId: string;
};
export interface InboxFilter {
    /** Whose inbox to show — `self` (default), a team, or a specific
     *  team-mate. */
    assignedTo: AssignedToFilter;
    /** Item status. {@code null} → all statuses. */
    status?: InboxItemStatus | null;
    /** Single tag filter. {@code null} → no tag filter. */
    tag?: string | null;
}
/**
 * Reactive wrapper around the inbox REST endpoints. One instance
 * per editor instance — exposes the active list, the selected
 * item, and mutation helpers.
 */
export declare function useInbox(): {
    items: Ref<InboxItemDto[]>;
    selected: Ref<InboxItemDto | null>;
    tags: Ref<string[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    filter: Ref<InboxFilter>;
    loadList: (filter: InboxFilter) => Promise<void>;
    loadOne: (id: string) => Promise<void>;
    loadTags: () => Promise<void>;
    clearSelection: () => void;
    answer: (id: string, outcome: AnswerOutcome, value?: Record<string, unknown> | null, reason?: string | null) => Promise<boolean>;
    archive: (id: string) => Promise<boolean>;
    unarchive: (id: string) => Promise<boolean>;
    dismiss: (id: string) => Promise<boolean>;
    delegate: (id: string, toUserId: string, note?: string | null) => Promise<boolean>;
};
//# sourceMappingURL=useInbox.d.ts.map