import { _ as __name } from './CodeEditor.vue_vue_type_style_index_0_scoped_d90b2dcd_lang-8oqwpJXu.js';

// src/utils/imperativeState.ts
var ImperativeState = class {
  /**
   * @param init - Function that creates the default state.
   */
  constructor(init) {
    this.init = init;
    this.records = this.init();
  }
  static {
    __name(this, "ImperativeState");
  }
  reset() {
    this.records = this.init();
  }
};

export { ImperativeState as I };
