const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/index-D13C5YuG.js","assets/SettingType-UjWoPh8Q.js"])))=>i.map(i=>d[i]);
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

const scriptRel = 'modulepreload';const assetsURL = function(dep) { return "/"+dep };const seen = {};const __vitePreload = function preload(baseModule, deps, importerUrl) {
  let promise = Promise.resolve();
  if (true && deps && deps.length > 0) {
    document.getElementsByTagName("link");
    const cspNonceMeta = document.querySelector(
      "meta[property=csp-nonce]"
    );
    const cspNonce = cspNonceMeta?.nonce || cspNonceMeta?.getAttribute("nonce");
    promise = Promise.allSettled(
      deps.map((dep) => {
        dep = assetsURL(dep);
        if (dep in seen) return;
        seen[dep] = true;
        const isCss = dep.endsWith(".css");
        const cssSelector = isCss ? '[rel="stylesheet"]' : "";
        if (document.querySelector(`link[href="${dep}"]${cssSelector}`)) {
          return;
        }
        const link = document.createElement("link");
        link.rel = isCss ? "stylesheet" : scriptRel;
        if (!isCss) {
          link.as = "script";
        }
        link.crossOrigin = "";
        link.href = dep;
        if (cspNonce) {
          link.setAttribute("nonce", cspNonce);
        }
        document.head.appendChild(link);
        if (isCss) {
          return new Promise((res, rej) => {
            link.addEventListener("load", res);
            link.addEventListener(
              "error",
              () => rej(new Error(`Unable to preload CSS for ${dep}`))
            );
          });
        }
      })
    );
  }
  function handlePreloadError(err) {
    const e = new Event("vite:preloadError", {
      cancelable: true
    });
    e.payload = err;
    window.dispatchEvent(e);
    if (!e.defaultPrevented) {
      throw err;
    }
  }
  return promise.then((res) => {
    for (const item of res || []) {
      if (item.status !== "rejected") continue;
      handlePreloadError(item.reason);
    }
    return baseModule().catch(handlePreloadError);
  });
};

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
    let exportModule = __mfModuleCache.share["@vance/components"];
    if (exportModule === undefined) {
      exportModule = __mfNormalizeShareModule(await __vitePreload(() => import('./index-D13C5YuG.js'),true?__vite__mapDeps([0,1]):void 0));
      __mfModuleCache.share["@vance/components"] = exportModule;
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
    const { VAlert: __mf_0, VBackButton: __mf_1, VButton: __mf_2, VCard: __mf_3, VCheckbox: __mf_4, VColorPicker: __mf_5, VDataList: __mf_6, VEmojiPicker: __mf_7, VEmptyState: __mf_8, VFileInput: __mf_9, VInput: __mf_10, VModal: __mf_11, VPagination: __mf_12, VSelect: __mf_13, VTagEditor: __mf_14, VTextarea: __mf_15 } = exportModule;
  
const __moduleExports = exportModule;

const _virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_components__loadShare__ = /*#__PURE__*/_mergeNamespaces({
  __proto__: null,
  VAlert: __mf_0,
  VBackButton: __mf_1,
  VButton: __mf_2,
  VCard: __mf_3,
  VCheckbox: __mf_4,
  VColorPicker: __mf_5,
  VDataList: __mf_6,
  VEmojiPicker: __mf_7,
  VEmptyState: __mf_8,
  VFileInput: __mf_9,
  VInput: __mf_10,
  VModal: __mf_11,
  VPagination: __mf_12,
  VSelect: __mf_13,
  VTagEditor: __mf_14,
  VTextarea: __mf_15,
  default: __mfDefaultExport
}, [__moduleExports]);

export { _virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_components__loadShare__ as _, __vitePreload as a, __mf_2 as b, __mf_0 as c, __mf_8 as d };
