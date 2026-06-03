import { _ as __vitePreload } from './preload-helper-C6a2snJ8.js';


const importMap = {
      
        "vue": async () => {
          let pkg = await __vitePreload(() => import('./_virtual_mf___mfe_internal__vance_addon_calendar__loadShare__vue__loadShare__.mjs-DvmOVNPO.js').then(n => n.G),true?[]:void 0,import.meta.url);
            return pkg;
        }
      
    };
      const usedShared = {
      
          "vue": {
            name: "vue",
            version: "3.5.0",
            scope: ["default"],
            loaded: false,
            from: "__mfe_internal__vance_addon_calendar",
            async get () {
              usedShared["vue"].loaded = true;
              const {"vue": pkgDynamicImport} = importMap;
              const res = await pkgDynamicImport();
              const exportModule = {...res};
              // All npm packages pre-built by vite will be converted to esm
              Object.defineProperty(exportModule, "__esModule", {
                value: true,
                enumerable: false
              });
              return function () {
                return exportModule
              }
            },
            shareConfig: {
              singleton: true,
              requiredVersion: "^3.5.0",
              
            }
          }
        
    };
      const usedRemotes = [
      ];

export { usedRemotes, usedShared };
