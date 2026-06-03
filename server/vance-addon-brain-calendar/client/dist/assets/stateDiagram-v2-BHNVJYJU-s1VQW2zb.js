import { s as styles_default, b as stateRenderer_v3_unified_default, a as stateDiagram_default, S as StateDB } from './chunk-AQP2D5EJ-CgGTUE_9.js';
import { _ as __name } from './CodeEditor.vue_vue_type_style_index_0_scoped_d90b2dcd_lang-8oqwpJXu.js';
import './chunk-55IACEB6-os7dL7CZ.js';
import './chunk-2J33WTMH-JcZVgR2c.js';
import './preload-helper-BelkbqnE.js';

// src/diagrams/state/stateDiagram-v2.ts
var diagram = {
  parser: stateDiagram_default,
  get db() {
    return new StateDB(2);
  },
  renderer: stateRenderer_v3_unified_default,
  styles: styles_default,
  init: /* @__PURE__ */ __name((cnf) => {
    if (!cnf.state) {
      cnf.state = {};
    }
    cnf.state.arrowMarkerAbsolute = cnf.arrowMarkerAbsolute;
  }, "init")
};

export { diagram };
