const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/index-Bo6mYkZA.js","assets/SettingType-UjWoPh8Q.js"])))=>i.map(i=>d[i]);
import { a as __vitePreload } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_components__loadShare__.mjs-BqfXZy3L.js';

function _mergeNamespaces(n, m) {
  for (var i = 0; i < m.length; i++) {
    const e = m[i];
    if (typeof e !== 'string' && !Array.isArray(e)) { for (const k in e) {
      if (k !== 'default' && !(k in n)) {
        const d = Object.getOwnPropertyDescriptor(e, k);
        if (d) {
          Object.defineProperty(n, k, d.get ? d : {
            enumerable: true,
            get: () => e[k]
          });
        }
      }
    } }
  }
  return Object.freeze(Object.defineProperty(n, Symbol.toStringTag, { value: 'Module' }));
}

const __mfCacheGlobalKey = "__mf_module_cache__";
globalThis[__mfCacheGlobalKey] ||= { share: {}, remote: {} };
globalThis[__mfCacheGlobalKey].share ||= {};
globalThis[__mfCacheGlobalKey].remote ||= {};
const __mfModuleCache = globalThis[__mfCacheGlobalKey];

    const __mfNormalizeShareModule = (mod) => {
      let current = mod;
      for (let i = 0; i < 5; i++) {
        const defaultExport = current?.default;
        if (!defaultExport || typeof defaultExport !== "object") break;
        const namedValues = Object.keys(current).filter((key) => key !== "default").map((key) => current[key]);
        if (namedValues.length > 0 && namedValues.some((value) => value !== undefined)) break;
        current = defaultExport;
      }
      return current;
    };
    let exportModule = __mfModuleCache.share["@vance/shared"];
    if (exportModule === undefined) {
      exportModule = __mfNormalizeShareModule(await __vitePreload(() => import('./index-Bo6mYkZA.js'),true?__vite__mapDeps([0,1]):void 0));
      __mfModuleCache.share["@vance/shared"] = exportModule;
    }
    const __mfDefaultExport = (() => {
      let current = exportModule;
      for (let i = 0; i < 5; i++) {
        const defaultExport = current?.default;
        if (!defaultExport || typeof defaultExport !== "object") return defaultExport ?? current;
        current = defaultExport;
      }
      return current;
    })();
    const { decodeJwt: __mf_0, isTokenValid: __mf_1, getTenantId: __mf_2, getUsername: __mf_3, getActiveSessionId: __mf_4, setActiveSessionId: __mf_5, clearLegacyAuth: __mf_6, clearAuth: __mf_7, getRememberedLogin: __mf_8, setRememberedLogin: __mf_9, clearRememberedLogin: __mf_10, getCalendarPlanner: __mf_11, createCalendarEvent: __mf_12, updateCalendarEvent: __mf_13, deleteCalendarEvent: __mf_14, rebuildCalendarPlanner: __mf_15, getKanbanBoard: __mf_16, moveKanbanCard: __mf_17, createKanbanCard: __mf_18, updateKanbanCard: __mf_19, deleteKanbanCard: __mf_20, rebuildKanbanBoard: __mf_21, fetchLinkPreview: __mf_22, _clearLinkPreviewCache: __mf_23, RestError: __mf_24, brainBaseUrl: __mf_25, brainFetch: __mf_26, brainFetchWithMeta: __mf_27, brainFetchBlob: __mf_28, brainFetchText: __mf_29, documentContentUrl: __mf_30, listSessions: __mf_31, searchSessions: __mf_32, patchSessionMetadata: __mf_33, archiveSession: __mf_34, reactivateSession: __mf_35, deleteSession: __mf_36, getSessionMessages: __mf_37, listSettingForms: __mf_38, getSettingForm: __mf_39, applySettingForm: __mf_40, validateSettingForm: __mf_41, resetSettingForm: __mf_42, listWizards: __mf_43, getWizard: __mf_44, renderWizard: __mf_45, configurePlatform: __mf_46, getStorage: __mf_47, getRestConfig: __mf_48, __resetPlatform: __mf_49, DEFAULT_RATE: __mf_50, MIN_RATE: __mf_51, MAX_RATE: __mf_52, DEFAULT_VOLUME: __mf_53, MIN_VOLUME: __mf_54, MAX_VOLUME: __mf_55, AUTO_LANGUAGE: __mf_56, SUPPORTED_SPEECH_LANGUAGES: __mf_57, getSpeechLanguage: __mf_58, setSpeechLanguage: __mf_59, resolveSpeechLanguage: __mf_60, getSpeechVoiceURI: __mf_61, setSpeechVoiceURI: __mf_62, getSpeechRate: __mf_63, setSpeechRate: __mf_64, getSpeechVolume: __mf_65, setSpeechVolume: __mf_66, getSpeakerEnabled: __mf_67, setSpeakerEnabled: __mf_68, stripMarkdown: __mf_69, markdownToSpeech: __mf_70, StorageKeys: __mf_71, WebSocketRequestError: __mf_72, WebSocketClosedError: __mf_73, BrainWebSocket: __mf_74 } = exportModule;
  
const __moduleExports = exportModule;

const _virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_shared__loadShare__ = /*#__PURE__*/_mergeNamespaces({
  __proto__: null,
  AUTO_LANGUAGE: __mf_56,
  BrainWebSocket: __mf_74,
  DEFAULT_RATE: __mf_50,
  DEFAULT_VOLUME: __mf_53,
  MAX_RATE: __mf_52,
  MAX_VOLUME: __mf_55,
  MIN_RATE: __mf_51,
  MIN_VOLUME: __mf_54,
  RestError: __mf_24,
  SUPPORTED_SPEECH_LANGUAGES: __mf_57,
  StorageKeys: __mf_71,
  WebSocketClosedError: __mf_73,
  WebSocketRequestError: __mf_72,
  __resetPlatform: __mf_49,
  _clearLinkPreviewCache: __mf_23,
  applySettingForm: __mf_40,
  archiveSession: __mf_34,
  brainBaseUrl: __mf_25,
  brainFetch: __mf_26,
  brainFetchBlob: __mf_28,
  brainFetchText: __mf_29,
  brainFetchWithMeta: __mf_27,
  clearAuth: __mf_7,
  clearLegacyAuth: __mf_6,
  clearRememberedLogin: __mf_10,
  configurePlatform: __mf_46,
  createCalendarEvent: __mf_12,
  createKanbanCard: __mf_18,
  decodeJwt: __mf_0,
  default: __mfDefaultExport,
  deleteCalendarEvent: __mf_14,
  deleteKanbanCard: __mf_20,
  deleteSession: __mf_36,
  documentContentUrl: __mf_30,
  fetchLinkPreview: __mf_22,
  getActiveSessionId: __mf_4,
  getCalendarPlanner: __mf_11,
  getKanbanBoard: __mf_16,
  getRememberedLogin: __mf_8,
  getRestConfig: __mf_48,
  getSessionMessages: __mf_37,
  getSettingForm: __mf_39,
  getSpeakerEnabled: __mf_67,
  getSpeechLanguage: __mf_58,
  getSpeechRate: __mf_63,
  getSpeechVoiceURI: __mf_61,
  getSpeechVolume: __mf_65,
  getStorage: __mf_47,
  getTenantId: __mf_2,
  getUsername: __mf_3,
  getWizard: __mf_44,
  isTokenValid: __mf_1,
  listSessions: __mf_31,
  listSettingForms: __mf_38,
  listWizards: __mf_43,
  markdownToSpeech: __mf_70,
  moveKanbanCard: __mf_17,
  patchSessionMetadata: __mf_33,
  reactivateSession: __mf_35,
  rebuildCalendarPlanner: __mf_15,
  rebuildKanbanBoard: __mf_21,
  renderWizard: __mf_45,
  resetSettingForm: __mf_42,
  resolveSpeechLanguage: __mf_60,
  searchSessions: __mf_32,
  setActiveSessionId: __mf_5,
  setRememberedLogin: __mf_9,
  setSpeakerEnabled: __mf_68,
  setSpeechLanguage: __mf_59,
  setSpeechRate: __mf_64,
  setSpeechVoiceURI: __mf_62,
  setSpeechVolume: __mf_66,
  stripMarkdown: __mf_69,
  updateCalendarEvent: __mf_13,
  updateKanbanCard: __mf_19,
  validateSettingForm: __mf_41
}, [__moduleExports]);

export { _virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_shared__loadShare__ as _, __mf_26 as a, __mf_30 as b };
