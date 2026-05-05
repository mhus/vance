import type { NavigatorScreenParams } from '@react-navigation/native';

/**
 * Inbox-tab stack: list → detail.
 */
export type InboxStackParamList = {
  InboxList: undefined;
  InboxDetail: { id: string };
};

/**
 * Documents-tab stack: project picker → document list (per project)
 * → document detail.
 */
export type DocumentsStackParamList = {
  ProjectList: undefined;
  DocumentList: { projectId: string; projectTitle: string };
  DocumentDetail: { id: string };
};

/**
 * Chat-tab stack: session picker → live chat.
 *
 * `ChatLive` carries the session id so deep-linking and navigation
 * resets can land directly on a specific conversation.
 */
export type ChatStackParamList = {
  ChatPicker: undefined;
  NewSession: undefined;
  ChatLive: { sessionId: string };
};

/**
 * Bottom-tab parameters. Each tab owns its own stack so the tab bar
 * stays visible across List → Detail navigation.
 */
export type MainTabParamList = {
  Inbox: NavigatorScreenParams<InboxStackParamList> | undefined;
  Chat: NavigatorScreenParams<ChatStackParamList> | undefined;
  Documents: NavigatorScreenParams<DocumentsStackParamList> | undefined;
};

/**
 * Root stack parameters. The Login screen is a peer of `Main` so the
 * `onUnauthorized` callback can `reset` directly onto it from any
 * depth without having to know the current tab.
 */
export type RootStackParamList = {
  Login: undefined;
  Main: NavigatorScreenParams<MainTabParamList> | undefined;
};
