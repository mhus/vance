import { Text, View } from 'react-native';
import { ProcessMode, TodoStatus, type TodoItem } from '@vance/generated';
import { normalizeEnum } from '@/util/enum';
import type { PlanMeta } from '@/hooks/useChatLive';

interface Props {
  mode: ProcessMode;
  todos: TodoItem[];
  planMeta: PlanMeta | null;
}

/**
 * Persistent Plan-Mode panel for the mobile chat. Mirrors the web
 * variant: a pending-plan banner while {@code mode === PLANNING} plus
 * a TodoList that stays visible across PLANNING and EXECUTING. Hides
 * itself when there is nothing to show — the parent screen does not
 * need to gate it.
 */
export function PlanModeIndicator({ mode, todos, planMeta }: Props) {
  const showPending = mode === ProcessMode.PLANNING && planMeta !== null;
  const showTodos = todos.length > 0;
  if (!showPending && !showTodos) return null;

  return (
    <View className="px-4 py-3 border-t border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-900">
      {showPending ? <PendingPlanCard meta={planMeta!} /> : null}
      {showTodos ? <TodoList items={todos} pendingPlanAbove={showPending} /> : null}
    </View>
  );
}

function PendingPlanCard({ meta }: { meta: PlanMeta }) {
  const title = meta.version > 1 ? `Plan v${meta.version}` : 'Plan';
  return (
    <View className="rounded-lg border border-blue-300 dark:border-blue-700 bg-blue-50 dark:bg-blue-900/30 px-3 py-2">
      <View className="flex-row items-center gap-2">
        <Text className="text-sm font-semibold text-blue-700 dark:text-blue-200">{title}</Text>
        <Text className="text-[10px] uppercase tracking-wide text-blue-700/70 dark:text-blue-200/70">
          wartet auf Freigabe
        </Text>
      </View>
      {meta.summary ? (
        <Text className="mt-1 text-xs text-slate-700 dark:text-slate-300">{meta.summary}</Text>
      ) : null}
      <Text className="mt-1 text-[11px] text-slate-500 dark:text-slate-400">
        Antworte mit „ok"/„mach so" für Freigabe oder mit Korrekturen.
      </Text>
    </View>
  );
}

function TodoList({ items, pendingPlanAbove }: { items: TodoItem[]; pendingPlanAbove: boolean }) {
  return (
    <View className={pendingPlanAbove ? 'mt-3' : ''}>
      <Text className="text-[10px] uppercase tracking-wide text-slate-500 dark:text-slate-400 font-semibold mb-1.5">
        Plan-Schritte
      </Text>
      <View className="gap-0.5">
        {items.map((item) => (
          <TodoRow key={item.id || item.content} item={item} />
        ))}
      </View>
    </View>
  );
}

function TodoRow({ item }: { item: TodoItem }) {
  const status = normalizeEnum(TodoStatus, item.status);
  const marker =
    status === TodoStatus.IN_PROGRESS ? '[~]'
      : status === TodoStatus.COMPLETED ? '[✓]'
        : '[ ]';
  const label =
    status === TodoStatus.IN_PROGRESS && item.activeForm && item.activeForm.trim().length > 0
      ? item.activeForm
      : item.content;
  const textCls =
    status === TodoStatus.IN_PROGRESS
      ? 'text-amber-700 dark:text-amber-300 font-semibold'
      : status === TodoStatus.COMPLETED
        ? 'text-slate-400 dark:text-slate-500 line-through'
        : 'text-slate-700 dark:text-slate-200';
  return (
    <View className="flex-row">
      <Text className={`text-xs font-mono ${textCls}`}>{marker}</Text>
      <Text className={`ml-2 text-xs flex-1 ${textCls}`}>{label}</Text>
    </View>
  );
}
