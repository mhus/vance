
// Windows temporarily needs this file, https://github.com/module-federation/vite/issues/68

    const importMap = {
      
        "vue": async () => {
          let pkg = await import("__mf__virtual/vance_addon_canvas__prebuild__vue__prebuild__.js")
          return pkg
        }
      ,
        "@vue-flow/core": async () => {
          let pkg = await import("__mf__virtual/vance_addon_canvas__prebuild___mf_0_vue_mf_2_flow_mf_1_core__prebuild__.js")
          return pkg
        }
      
    }
      const usedShared = {
      
          "vue": {
            name: "vue",
            version: "3.5.38",
            scope: ["default"],
            loaded: false,
            from: "vance_addon_canvas",
            async get () {
              usedShared["vue"].loaded = true
              const {"vue": pkgDynamicImport} = importMap 
              const res = await pkgDynamicImport()
              const exportModule = {...res}
              // All npm packages pre-built by vite will be converted to esm
              Object.defineProperty(exportModule, "__esModule", {
                value: true,
                enumerable: false
              })
              return function () {
                return exportModule
              }
            },
            shareConfig: {
              singleton: true,
              requiredVersion: "^3.5.0"
            }
          }
        ,
          "@vue-flow/core": {
            name: "@vue-flow/core",
            version: "1.48.2",
            scope: ["default"],
            loaded: false,
            from: "vance_addon_canvas",
            async get () {
              usedShared["@vue-flow/core"].loaded = true
              const {"@vue-flow/core": pkgDynamicImport} = importMap 
              const res = await pkgDynamicImport()
              const exportModule = {...res}
              // All npm packages pre-built by vite will be converted to esm
              Object.defineProperty(exportModule, "__esModule", {
                value: true,
                enumerable: false
              })
              return function () {
                return exportModule
              }
            },
            shareConfig: {
              singleton: true,
              requiredVersion: "^1.48.0"
            }
          }
        
    }
      const usedRemotes = [
      ]
      export {
        usedShared,
        usedRemotes
      }
      