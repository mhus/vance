import { s as styles_default, c as classRenderer_v3_unified_default, a as classDiagram_default, C as ClassDB } from './chunk-727SXJPM-D8mL73Lx.js';
import { _ as __name } from './CodeEditor.vue_vue_type_style_index_0_scoped_3b7f4c02_lang-CdGlz66Z.js';
import './chunk-FMBD7UC4-DQKzsr0r.js';
import './chunk-ND2GUHAM-B_neubBR.js';
import './chunk-55IACEB6-C6ihza36.js';
import './chunk-2J33WTMH-BCVd7obg.js';
import './preload-helper-C6a2snJ8.js';

// src/diagrams/class/classDiagram.ts
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
