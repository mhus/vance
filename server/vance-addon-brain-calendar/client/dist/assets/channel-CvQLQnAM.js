import { ai as Utils, aj as Color } from './CodeEditor.vue_vue_type_style_index_0_scoped_d90b2dcd_lang-8oqwpJXu.js';

/* IMPORT */
/* MAIN */
const channel = (color, channel) => {
    return Utils.lang.round(Color.parse(color)[channel]);
};

export { channel as c };
