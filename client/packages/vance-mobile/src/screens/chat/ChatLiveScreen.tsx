import { useEffect, useMemo, useRef } from 'react';
import { FlatList, KeyboardAvoidingView, Platform, View } from 'react-native';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ChatRole, type ChatMessageDto } from '@vance/generated';
import { useChatLive, type ChatConnectionState } from '@/hooks/useChatLive';
import { MobileShell, VAlert, VLoading } from '@/components';
import { MessageBubble } from './components/MessageBubble';
import { SpeakerToggle } from './components/SpeakerToggle';
import { ChatComposer } from './components/ChatComposer';
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
          <ConnectionDot state={live.connectionState} />
          <SpeakerToggle />
        </View>
      }
      footer={<ChatComposer disabled={live.connectionState !== 'open'} onSend={live.send} />}
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
      </KeyboardAvoidingView>
    </MobileShell>
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

