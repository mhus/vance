// Barrel for the wiki addon's client surface — the mount wrapper for
// the application:wiki kind plus the wiki REST helpers. The block-editor
// implementation itself lives in @vance/block-editor (shared package);
// WikiAppKind mounts its WorkPageEditor from there with wikilink
// resolution injected.

export { default as WikiAppKind } from './WikiAppKind.vue';
export {
  scanWiki,
  rebuildWiki,
  createWikiPage,
  deleteWikiPage,
  resolveWikiLink,
  wikiBacklinks,
  wikiRecent,
  searchWikiDocuments,
} from './api';
export { slugify } from './slug';
export type { WikiView } from './generated/wiki/WikiView';
export type { WikiPageView } from './generated/wiki/WikiPageView';
export type { WikiSpaceView } from './generated/wiki/WikiSpaceView';
export type { WikiResolveResponse } from './generated/wiki/WikiResolveResponse';
export type { WikiRebuildResponse } from './generated/wiki/WikiRebuildResponse';
