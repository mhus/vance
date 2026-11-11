import { computed, onBeforeUnmount, ref, type ComputedRef } from 'vue';
import { fetchFieldSuggestions, getUsername } from '@vance/shared';

const SUGGEST_DEBOUNCE_MS = 200;

export interface AssigneeSuggestions {
  /** The signed-in user's login, `null` when unknown. */
  me: string | null;
  /** Options for a combobox: fetched project members, the current user always first. */
  options: ComputedRef<{ value: string; label: string }[]>;
  /** Wire to the combobox's `query` event. */
  onQuery: (query: string) => void;
}

/**
 * Assignee suggestions for a card field: project members from the brain's
 * `project-members` suggestion source (users with a project role, teams
 * expanded). The current user is always offered, so the list is useful before
 * anything is typed — the source itself answers nothing for a blank query.
 */
export function useAssigneeSuggestions(projectId: () => string): AssigneeSuggestions {
  const me = getUsername();
  const hits = ref<{ value: string; label: string }[]>([]);
  let timer: ReturnType<typeof setTimeout> | null = null;
  let seq = 0;

  const options = computed(() => {
    const out = [...hits.value];
    if (me && !out.some((o) => o.value === me)) out.unshift({ value: me, label: me });
    return out;
  });

  function onQuery(query: string): void {
    if (timer) clearTimeout(timer);
    const text = query.trim();
    if (!text) {
      hits.value = [];
      return;
    }
    timer = setTimeout(() => {
      timer = null;
      const mine = ++seq;
      fetchFieldSuggestions('project-members', text, projectId())
        .then((found) => {
          if (mine !== seq) return;
          hits.value = found
            .filter((h) => h.values.length > 0)
            .map((h) => ({
              value: h.values[0],
              label: h.description ? `${h.label} (${h.description})` : h.label,
            }));
        })
        .catch(() => {
          if (mine === seq) hits.value = [];
        });
    }, SUGGEST_DEBOUNCE_MS);
  }

  onBeforeUnmount(() => {
    if (timer) clearTimeout(timer);
  });

  return { me, options, onQuery };
}
