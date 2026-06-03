import { s as styles_default, c as classRenderer_v3_unified_default, a as classDiagram_default, C as ClassDB } from './chunk-727SXJPM-DvBY6ph4.js';
import { _ as __name } from './CodeEditor.vue_vue_type_style_index_0_scoped_d90b2dcd_lang-8oqwpJXu.js';
import './chunk-FMBD7UC4-DnZB2OVh.js';
import './chunk-ND2GUHAM-Dtju_1K_.js';
import './chunk-55IACEB6-os7dL7CZ.js';
import './chunk-2J33WTMH-JcZVgR2c.js';
import './preload-helper-BelkbqnE.js';

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
