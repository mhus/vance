import { a as __vitePreload } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare___mf_0_vance_mf_1_components__loadShare__.mjs-BqfXZy3L.js';


const importMap = {
      
        "@vance/components": async () => {
          let pkg = await __vitePreload(() => import('./index-D13C5YuG.js'),true?[]:void 0);
            return pkg;
        }
      ,
        "@vance/shared": async () => {
          let pkg = await __vitePreload(() => import('./index-Bo6mYkZA.js'),true?[]:void 0);
            return pkg;
        }
      ,
        "vue": async () => {
          let pkg = await __vitePreload(() => import('./_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare__vue__loadShare__.mjs-2_nCHSZU.js').then(n => n.A),true?[]:void 0);
            return pkg;
        }
      
    };
      const usedShared = {
      
          "@vance/components": {
            name: "@vance/components",
            version: "1.0.0",
            scope: ["default"],
            loaded: false,
            from: "__mfe_internal__vance_addon_slideshow",
            async get () {
              usedShared["@vance/components"].loaded = true;
              const {"@vance/components": pkgDynamicImport} = importMap;
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
              requiredVersion: "^1.0.0",
              
            }
          }
        ,
          "@vance/shared": {
            name: "@vance/shared",
            version: "1.0.0",
            scope: ["default"],
            loaded: false,
            from: "__mfe_internal__vance_addon_slideshow",
            async get () {
              usedShared["@vance/shared"].loaded = true;
              const {"@vance/shared": pkgDynamicImport} = importMap;
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
              requiredVersion: "^1.0.0",
              
            }
          }
        ,
          "vue": {
            name: "vue",
            version: "3.5.0",
            scope: ["default"],
            loaded: false,
            from: "__mfe_internal__vance_addon_slideshow",
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
