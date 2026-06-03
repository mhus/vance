import { s as styles_default, c as classRenderer_v3_unified_default, a as classDiagram_default, C as ClassDB } from './chunk-727SXJPM-d7DlSWcZ.js';
import { _ as __name } from './CodeEditor.vue_vue_type_style_index_0_scoped_d90b2dcd_lang-B0SRY8Hb.js';
import './chunk-FMBD7UC4-CW3HVxT0.js';
import './chunk-ND2GUHAM-0hBavtSs.js';
import './chunk-55IACEB6-CYdQw3cm.js';
import './chunk-2J33WTMH-B_8_HgUZ.js';
import './preload-helper-C6a2snJ8.js';

// src/diagrams/class/classDiagram-v2.ts
var diagram = {
  parser: classDiagram_default,
  get db() {
    return new ClassDB();
  },
  renderer: classRenderer_v3_unified_default,
  styles: styles_default,
  init: /* @__PURE__ */ __name((cnf) => {
    if (!cnf.class) {
      cnf.class = {};
    }
    cnf.class.arrowMarkerAbsolute = cnf.arrowMarkerAbsolute;
  }, "init")
};

export { diagram };
