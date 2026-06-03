import { a as __vitePreload } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_components__loadShare__.mjs-BqfXZy3L.js';

const __mfCacheGlobalKey = "__mf_module_cache__";
globalThis[__mfCacheGlobalKey] ||= { share: {}, remote: {} };
globalThis[__mfCacheGlobalKey].share ||= {};
globalThis[__mfCacheGlobalKey].remote ||= {};
const __mfModuleCache = globalThis[__mfCacheGlobalKey];

    let hostInitPromise;
    async function initHost() {
      if (!hostInitPromise) {
        hostInitPromise = (async () => {
          
          const remoteEntry = await __vitePreload(() => import('../remoteEntry.js'),true?[]:void 0);
          const runtime = await remoteEntry.init();
          const usedShared = {
      "@vance/components": {
            shareConfig: {
              singleton: true,
              requiredVersion: "^1.0.0",
              
            }
          },
"@vance/shared": {
            shareConfig: {
              singleton: true,
              requiredVersion: "^1.0.0",
              
            }
          },
"vue": {
            shareConfig: {
              singleton: true,
              requiredVersion: "^3.5.0",
              
            }
          }
    };
          const __mfNormalizeRuntimeShare = (mod) => {
            let current = mod;
            for (let i = 0; i < 5; i++) {
              const defaultExport = current?.default;
              if (!defaultExport || typeof defaultExport !== "object" || Object.keys(defaultExport).length === 0) break;
              const namedValues = Object.keys(current).filter((key) => key !== "default").map((key) => current[key]);
              if (namedValues.length > 0 && namedValues.some((value) => value !== undefined)) break;
              current = defaultExport;
            }
            return current;
          };
          
          for (const [pkg, share] of Object.entries(usedShared)) {
            const cacheKey = share.shareConfig?.singleton || !share.version ? pkg : `${pkg}@${share.version}`;
            if (__mfModuleCache.share[cacheKey] !== undefined) {
              continue;
            }
            await runtime.loadShare(pkg, {
              customShareInfo: { shareConfig: share.shareConfig }
            }).then((factory) => {
              const mod = typeof factory === "function" ? factory() : factory;
              return Promise.resolve(mod).then((resolved) => {
                __mfModuleCache.share[cacheKey] = __mfNormalizeRuntimeShare(resolved);
              });
            });
          }
          
          const __mfRemotePreloads = [];
          await Promise.all(__mfRemotePreloads);
          return runtime;
        })();
      }
      return hostInitPromise;
    }
    hostInitPromise = initHost();

export { hostInitPromise, initHost };
