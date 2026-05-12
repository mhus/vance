import { useEffect, useMemo, useRef } from 'react';
import { FlatList, KeyboardAvoidingView, Platform, Text, View } from 'react-native';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ChatRole, ProcessMode, type ChatMessageDto } from '@vance/generated';
import { useChatLive, type ChatConnectionState } from '@/hooks/useChatLive';
import { MobileShell, VAlert, VBadge, VLoading } from '@/components';
import { MessageBubble } from './components/MessageBubble';
import { SpeakerToggle } from './components/SpeakerToggle';
import { ChatComposer } from './components/ChatComposer';
import { PlanModeIndicator } from './components/PlanModeIndicator';
import type { ChatStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<ChatStackParamList, 'ChatLive'>;
type Route = RouteProp<ChatStackParamList, 'ChatLive'>;

interface BubbleItem {
  key: string;
  role: ChatRole;
  content: string;
  streaming: boolean;
}

export default function ChatLiveScreen() {
  const nav = useNavigation<Nav>();
  const { sessionId } = useRoute<Route>().params;
  const live = useChatLive(sessionId);
  const listRef = useRef<FlatList<BubbleItem>>(null);

  // Combined render-list: history + active stream drafts. Stream
  // drafts are appended after the messages so they look "in flight"
  // at the bottom of the conversation.
  const items = useMemo<BubbleItem[]>(() => {
    const out: BubbleItem[] = live.messages.map((m: ChatMessageDto) => ({
      key: m.messageId,
      role: m.role,
      content: m.content,
      streaming: false,
    }));
    for (const [tpId, draft] of live.streamingDrafts) {
      out.push({
        key: `stream:${tpId}`,
        role: draft.role,
        content: draft.chunk,
        streaming: true,
      });
    }
    return out;
  }, [live.messages, live.streamingDrafts]);

  // Auto-scroll to bottom when new content arrives.
  useEffect(() => {
    if (items.length === 0) return;
    requestAnimationFrame(() => {
      listRef.current?.scrollToEnd({ animated: true });
    });
  }, [items.length, items[items.length - 1]?.content]);

  return (
    <MobileShell
      title={live.sessionDisplay}
      onBack={() => nav.goBack()}
      trailing={
        <View className="flex-row items-center gap-1">
          <ModeBadge mode={live.chatProcessMode} />
          <ConnectionDot state={live.connectionState} />
          <SpeakerToggle />
        </View>
      }
      footer={
        <View>
          <PlanModeIndicator
            mode={live.chatProcessMode}
            todos={live.chatTodos}
            planMeta={live.planMeta}
          />
          <ChatComposer disabled={live.connectionState !== 'open'} onSend={live.send} />
        </View>
      }
    >
      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        {live.connectionState === 'connecting' && items.length === 0 ? (
          <VLoading variant="centered" label="Connecting…" />
        ) : (
          <>
            {live.connectionState === 'reconnecting' ? (
              <View className="px-4 pt-2">
                <VAlert variant="warning">
                  Connection lost — retrying…
                </VAlert>
              </View>
            ) : null}
            <FlatList
              ref={listRef}
              data={items}
              keyExtractor={(item) => item.key}
              renderItem={({ item }) => (
                <MessageBubble role={item.role} content={item.content} streaming={item.streaming} />
              )}
              contentContainerStyle={{ paddingVertical: 8 }}
              onContentSizeChange={() => listRef.current?.scrollToEnd({ animated: false })}
            />
          </>
        )}
        {live.progressHint !== null ? <ProgressOverlay text={live.progressHint} /> : null}
      </KeyboardAvoidingView>
    </MobileShell>
  );
}

/**
 * Floating, low-emphasis hint just above the composer. Echoes the
 * latest `process-progress` frame so the user sees that something is
 * happening (token counting, planning, status text). Auto-clears
 * after ~1.2 s — the hook resets the state, the View unmounts, and
 * we get a clean slate for the next frame.
 */
function ProgressOverlay({ text }: { text: string }) {
  return (
    <View
      pointerEvents="none"
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: 8,
        alignItems: 'center',
      }}
    >
      <View className="px-3 py-1 rounded-full bg-slate-800/70 dark:bg-slate-700/70">
        <Text className="text-xs text-white" numberOfLines={1}>
          {text}
        </Text>
      </View>
    </View>
  );
}

function ModeBadge({ mode }: { mode: ProcessMode }) {
  // NORMAL is the default — no badge needed. Keeps the trailing slot
  // tidy when the chat is just plain Q&A.
  if (mode === ProcessMode.NORMAL) return null;
  const label =
    mode === ProcessMode.PLANNING ? 'planning'
      : mode === ProcessMode.EXECUTING ? 'executing'
        : mode === ProcessMode.EXPLORING ? 'exploring'
          : '';
  if (!label) return null;
  return (
    <View className="px-1">
      <VBadge variant="primary">{label}</VBadge>
    </View>
  );
}

function ConnectionDot({ state }: { state: ChatConnectionState }) {
  const color =
    state === 'open'
      ? 'bg-green-500'
      : state === 'reconnecting' || state === 'connecting'
        ? 'bg-amber-500'
        : 'bg-slate-400';
  return (
    <View className="px-1">
      <View className={`h-2 w-2 rounded-full ${color}`} />
    </View>
  );
}

