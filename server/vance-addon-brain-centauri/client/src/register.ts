// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
import { FeedSourceParseError, parseFeedSourceDoc, serializeFeedSourceDoc, type FeedSourceDoc } from './feedSourceCodec';
import { feedSourceSettingsProvider } from './feedSourceSettingsProvider';

const FeedsAppKind = defineAsyncComponent(() => import('./FeedsAppKind.vue'));
const FeedSourceFormView = defineAsyncComponent(() => import('./FeedSourceFormView.vue'));

export function register(): void {

  console.log('[vance-addon/centauri] register() called');

  // Application kind: _app.yaml manifests with app: feeds.
  // Resolved by explicit id lookup (resolveKind('application:feeds')).
  registerKind({
    id: 'application:feeds',
    matches: () => false,
    view: FeedsAppKind,
  });

  // Feed source kind: one operator document under _vance/config/feeds/. The
  // settings area rides along — the host's "Bereiche" tab inventories these
  // documents and opens them in this addon's form view, inline.
  registerKind<FeedSourceDoc>({
    id: 'vance-feed-source',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-feed-source',
    parse: parseFeedSourceDoc,
    serialize: serializeFeedSourceDoc,
    isParseError: (e) => e instanceof FeedSourceParseError,
    view: FeedSourceFormView,
    settingsProvider: feedSourceSettingsProvider,
  });
}
