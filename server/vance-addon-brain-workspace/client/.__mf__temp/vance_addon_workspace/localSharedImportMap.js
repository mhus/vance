
// Windows temporarily needs this file, https://github.com/module-federation/vite/issues/68

    const importMap = {
      
        "vue": async () => {
          let pkg = await import("__mf__virtual/vance_addon_workspace__prebuild__vue__prebuild__.js")
          return pkg
        }
      ,
        "@tiptap/vue-3": async () => {
          let pkg = await import("__mf__virtual/vance_addon_workspace__prebuild___mf_0_tiptap_mf_1_vue_mf_2_3__prebuild__.js")
          return pkg
        }
      ,
        "@tiptap/core": async () => {
          let pkg = await import("__mf__virtual/vance_addon_workspace__prebuild___mf_0_tiptap_mf_1_core__prebuild__.js")
          return pkg
        }
      
    }
      const usedShared = {
      
          "vue": {
            name: "vue",
            version: "3.5.38",
            scope: ["default"],
            loaded: false,
            from: "vance_addon_workspace",
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
          "@tiptap/vue-3": {
            name: "@tiptap/vue-3",
            version: "2.27.2",
            scope: ["default"],
            loaded: false,
            from: "vance_addon_workspace",
            async get () {
              usedShared["@tiptap/vue-3"].loaded = true
              const {"@tiptap/vue-3": pkgDynamicImport} = importMap 
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
              requiredVersion: "^2.10.0"
            }
          }
        ,
          "@tiptap/core": {
            name: "@tiptap/core",
            version: "2.27.2",
            scope: ["default"],
            loaded: false,
            from: "vance_addon_workspace",
            async get () {
              usedShared["@tiptap/core"].loaded = true
              const {"@tiptap/core": pkgDynamicImport} = importMap 
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
              requiredVersion: "^2.10.0"
            }
          }
        
    }
      const usedRemotes = [
      ]
      export {
        usedShared,
        usedRemotes
      }
      