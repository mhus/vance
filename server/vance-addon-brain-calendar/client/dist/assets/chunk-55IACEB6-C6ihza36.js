import { _ as __name, d as select } from './CodeEditor.vue_vue_type_style_index_0_scoped_3b7f4c02_lang-CdGlz66Z.js';

var getDiagramElement = /* @__PURE__ */ __name((id, securityLevel) => {
  let sandboxElement;
  if (securityLevel === "sandbox") {
    sandboxElement = select("#i" + id);
  }
  const root = securityLevel === "sandbox" ? select(sandboxElement.nodes()[0].contentDocument.body) : select("body");
  const svg = root.select(`[id="${id}"]`);
  return svg;
}, "getDiagramElement");

export { getDiagramElement as g };
