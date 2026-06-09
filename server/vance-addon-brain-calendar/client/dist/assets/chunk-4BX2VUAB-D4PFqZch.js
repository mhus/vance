import { _ as __name } from './CodeEditor.vue_vue_type_style_index_0_scoped_3b7f4c02_lang-CdGlz66Z.js';

// src/diagrams/common/populateCommonDb.ts
function populateCommonDb(ast, db) {
  if (ast.accDescr) {
    db.setAccDescription?.(ast.accDescr);
  }
  if (ast.accTitle) {
    db.setAccTitle?.(ast.accTitle);
  }
  if (ast.title) {
    db.setDiagramTitle?.(ast.title);
  }
}
__name(populateCommonDb, "populateCommonDb");

export { populateCommonDb as p };
