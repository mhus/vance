import { a as __mf_93, c as __mf_80, f as __mf_132, g as __mf_83, t as __mf_58, u as __mf_139, h as __mf_84, i as __mf_61, l as __mf_82, j as __mf_90, k as __mf_69, s as __mf_138, b as __mf_45, v as __mf_168, w as __mf_23, x as __mf_21, r as __mf_24, y as __mf_161, d as __mf_126, z as __mf_130 } from './_virtual_mf___mfe_internal__vance_addon_slideshow__loadShare__vue__loadShare__.mjs-2_nCHSZU.js';
import { S as SessionColor } from './SettingType-UjWoPh8Q.js';

const _sfc_main$f = /* @__PURE__ */ __mf_93({
  __name: "VAlert",
  props: {
    variant: { default: "info" }
  },
  setup(__props) {
    const props = __props;
    const variantClass = __mf_80(() => {
      switch (props.variant) {
        case "info":
          return "alert-info";
        case "warning":
          return "alert-warning";
        case "error":
          return "alert-error";
        case "success":
          return "alert-success";
      }
    });
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", {
        role: "alert",
        class: __mf_58(["alert", variantClass.value])
      }, [
        __mf_139(_ctx.$slots, "default")
      ], 2);
    };
  }
});

const _sfc_main$e = /* @__PURE__ */ __mf_93({
  __name: "VBackButton",
  props: {
    label: { default: "Back" }
  },
  emits: ["click"],
  setup(__props) {
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("button", {
        type: "button",
        class: "btn btn-ghost btn-sm gap-1",
        onClick: _cache[0] || (_cache[0] = ($event) => _ctx.$emit("click"))
      }, [
        _cache[1] || (_cache[1] = __mf_84("span", { "aria-hidden": "true" }, "‹", -1)),
        __mf_84("span", null, __mf_61(__props.label), 1)
      ]);
    };
  }
});

const _hoisted_1$d = ["href"];
const _hoisted_2$d = {
  key: 0,
  class: "loading loading-spinner loading-sm"
};
const _hoisted_3$d = ["type", "disabled"];
const _hoisted_4$c = {
  key: 0,
  class: "loading loading-spinner loading-sm"
};
const _sfc_main$d = /* @__PURE__ */ __mf_93({
  __name: "VButton",
  props: {
    variant: { default: "primary" },
    href: {},
    type: { default: "button" },
    loading: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    block: { type: Boolean, default: false },
    size: { default: "md" }
  },
  emits: ["click"],
  setup(__props) {
    const props = __props;
    const variantClass = __mf_80(() => {
      switch (props.variant) {
        case "primary":
          return "btn-primary";
        case "secondary":
          return "btn-secondary";
        case "ghost":
          return "btn-ghost";
        case "danger":
          return "btn-error";
        case "link":
          return "btn-link";
      }
    });
    const sizeClass = __mf_80(() => props.size === "sm" ? "btn-sm" : "");
    return (_ctx, _cache) => {
      return __props.href ? (__mf_132(), __mf_83("a", {
        key: 0,
        href: __props.href,
        class: __mf_58(["btn", variantClass.value, sizeClass.value, { "btn-block": __props.block, "btn-disabled": __props.disabled }]),
        onClick: _cache[0] || (_cache[0] = (e) => _ctx.$emit("click", e))
      }, [
        __props.loading ? (__mf_132(), __mf_83("span", _hoisted_2$d)) : __mf_82("", true),
        __mf_139(_ctx.$slots, "default")
      ], 10, _hoisted_1$d)) : (__mf_132(), __mf_83("button", {
        key: 1,
        type: __props.type,
        disabled: __props.disabled || __props.loading,
        class: __mf_58(["btn", variantClass.value, sizeClass.value, { "btn-block": __props.block }]),
        onClick: _cache[1] || (_cache[1] = (e) => _ctx.$emit("click", e))
      }, [
        __props.loading ? (__mf_132(), __mf_83("span", _hoisted_4$c)) : __mf_82("", true),
        __mf_139(_ctx.$slots, "default")
      ], 10, _hoisted_3$d));
    };
  }
});

const _hoisted_1$c = { class: "card bg-base-100 shadow-xl" };
const _hoisted_2$c = { class: "card-body" };
const _hoisted_3$c = {
  key: 0,
  class: "card-title"
};
const _hoisted_4$b = {
  key: 1,
  class: "card-actions justify-end mt-4"
};
const _sfc_main$c = /* @__PURE__ */ __mf_93({
  __name: "VCard",
  props: {
    title: {}
  },
  setup(__props) {
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$c, [
        __mf_84("div", _hoisted_2$c, [
          __props.title || _ctx.$slots.header ? (__mf_132(), __mf_83("h2", _hoisted_3$c, [
            __mf_139(_ctx.$slots, "header", {}, () => [
              __mf_90(__mf_61(__props.title), 1)
            ])
          ])) : __mf_82("", true),
          __mf_139(_ctx.$slots, "default"),
          _ctx.$slots.actions ? (__mf_132(), __mf_83("div", _hoisted_4$b, [
            __mf_139(_ctx.$slots, "actions")
          ])) : __mf_82("", true)
        ])
      ]);
    };
  }
});

const _hoisted_1$b = { class: "form-control" };
const _hoisted_2$b = { class: "cursor-pointer label justify-start gap-2 py-1" };
const _hoisted_3$b = ["checked", "disabled"];
const _hoisted_4$a = {
  key: 0,
  class: "label-text"
};
const _hoisted_5$9 = {
  key: 0,
  class: "text-xs opacity-70 mt-1"
};
const _sfc_main$b = /* @__PURE__ */ __mf_93({
  __name: "VCheckbox",
  props: {
    modelValue: { type: Boolean },
    label: {},
    help: {},
    disabled: { type: Boolean, default: false }
  },
  emits: ["update:modelValue"],
  setup(__props, { emit: __emit }) {
    const emit = __emit;
    function onChange(event) {
      emit("update:modelValue", event.target.checked);
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("label", _hoisted_1$b, [
        __mf_84("span", _hoisted_2$b, [
          __mf_84("input", {
            type: "checkbox",
            class: "checkbox checkbox-sm",
            checked: __props.modelValue,
            disabled: __props.disabled,
            onChange
          }, null, 40, _hoisted_3$b),
          __props.label ? (__mf_132(), __mf_83("span", _hoisted_4$a, __mf_61(__props.label), 1)) : __mf_82("", true)
        ]),
        __props.help ? (__mf_132(), __mf_83("span", _hoisted_5$9, __mf_61(__props.help), 1)) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$a = { class: "flex flex-col gap-2" };
const _hoisted_2$a = {
  key: 0,
  class: "text-xs opacity-70"
};
const _hoisted_3$a = { class: "flex flex-wrap gap-2" };
const _hoisted_4$9 = ["disabled", "aria-label", "aria-pressed", "onClick"];
const _hoisted_5$8 = ["disabled", "aria-pressed"];
const _sfc_main$a = /* @__PURE__ */ __mf_93({
  __name: "VColorPicker",
  props: {
    modelValue: {},
    allowClear: { type: Boolean, default: true },
    disabled: { type: Boolean, default: false },
    label: {}
  },
  emits: ["update:modelValue"],
  setup(__props, { emit: __emit }) {
    function colorName(c) {
      return SessionColor[c];
    }
    const props = __props;
    const emit = __emit;
    const SWATCHES = [
      { value: SessionColor.SLATE, bg: "bg-slate-500", ring: "ring-slate-500" },
      { value: SessionColor.RED, bg: "bg-red-500", ring: "ring-red-500" },
      { value: SessionColor.ORANGE, bg: "bg-orange-500", ring: "ring-orange-500" },
      { value: SessionColor.AMBER, bg: "bg-amber-500", ring: "ring-amber-500" },
      { value: SessionColor.GREEN, bg: "bg-green-500", ring: "ring-green-500" },
      { value: SessionColor.TEAL, bg: "bg-teal-500", ring: "ring-teal-500" },
      { value: SessionColor.CYAN, bg: "bg-cyan-500", ring: "ring-cyan-500" },
      { value: SessionColor.BLUE, bg: "bg-blue-500", ring: "ring-blue-500" },
      { value: SessionColor.INDIGO, bg: "bg-indigo-500", ring: "ring-indigo-500" },
      { value: SessionColor.PURPLE, bg: "bg-purple-500", ring: "ring-purple-500" },
      { value: SessionColor.PINK, bg: "bg-pink-500", ring: "ring-pink-500" },
      { value: SessionColor.ROSE, bg: "bg-rose-500", ring: "ring-rose-500" }
    ];
    const current = __mf_80(() => props.modelValue ?? null);
    function pick(value) {
      if (props.disabled) return;
      emit("update:modelValue", value);
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$a, [
        __props.label ? (__mf_132(), __mf_83("span", _hoisted_2$a, __mf_61(__props.label), 1)) : __mf_82("", true),
        __mf_84("div", _hoisted_3$a, [
          (__mf_132(), __mf_83(__mf_69, null, __mf_138(SWATCHES, (swatch) => {
            return __mf_84("button", {
              key: swatch.value,
              type: "button",
              disabled: __props.disabled,
              class: __mf_58(["size-6 rounded-full ring-2 ring-offset-2 ring-offset-base-100 transition-opacity", [
                swatch.bg,
                swatch.ring,
                current.value === swatch.value ? "opacity-100" : "opacity-60 ring-transparent hover:opacity-100",
                __props.disabled ? "cursor-not-allowed" : "cursor-pointer"
              ]]),
              "aria-label": colorName(swatch.value),
              "aria-pressed": current.value === swatch.value,
              onClick: ($event) => pick(swatch.value)
            }, null, 10, _hoisted_4$9);
          }), 64)),
          __props.allowClear ? (__mf_132(), __mf_83("button", {
            key: 0,
            type: "button",
            disabled: __props.disabled,
            class: __mf_58(["size-6 rounded-full ring-2 ring-offset-2 ring-offset-base-100 transition-opacity bg-base-200 border border-base-300", [
              current.value === null ? "opacity-100 ring-base-content" : "opacity-60 ring-transparent hover:opacity-100",
              __props.disabled ? "cursor-not-allowed" : "cursor-pointer"
            ]]),
            "aria-label": "no-color",
            "aria-pressed": current.value === null,
            onClick: _cache[0] || (_cache[0] = ($event) => pick(null))
          }, [..._cache[1] || (_cache[1] = [
            __mf_84("span", { class: "text-xs opacity-70" }, "×", -1)
          ])], 10, _hoisted_5$8)) : __mf_82("", true)
        ])
      ]);
    };
  }
});

const _hoisted_1$9 = { class: "flex flex-col gap-2" };
const _hoisted_2$9 = ["onClick"];
const _hoisted_3$9 = { class: "card-body p-4" };
const _sfc_main$9 = /* @__PURE__ */ __mf_93({
  __name: "VDataList",
  props: {
    items: {},
    itemKey: {},
    selectable: { type: Boolean, default: false },
    selectedId: { default: null }
  },
  emits: ["select"],
  setup(__props) {
    function keyOf(item, index, extractor) {
      if (extractor) return extractor(item, index);
      return item.id ?? index;
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("ul", _hoisted_1$9, [
        (__mf_132(true), __mf_83(__mf_69, null, __mf_138(__props.items, (item, index) => {
          return __mf_132(), __mf_83("li", {
            key: keyOf(item, index, __props.itemKey),
            class: __mf_58([
              "card bg-base-100 shadow-sm border border-base-300",
              __props.selectable ? "cursor-pointer hover:border-primary" : "",
              __props.selectedId !== null && item.id === __props.selectedId ? "border-primary" : ""
            ]),
            onClick: ($event) => __props.selectable && _ctx.$emit("select", item)
          }, [
            __mf_84("div", _hoisted_3$9, [
              __mf_139(_ctx.$slots, "default", {
                item,
                index
              })
            ])
          ], 10, _hoisted_2$9);
        }), 128))
      ]);
    };
  }
});

const _hoisted_1$8 = { class: "flex flex-col gap-1" };
const _hoisted_2$8 = {
  key: 0,
  class: "text-xs opacity-70"
};
const _hoisted_3$8 = { class: "relative inline-block" };
const _hoisted_4$8 = ["disabled", "aria-label"];
const _hoisted_5$7 = { key: 0 };
const _hoisted_6$3 = {
  key: 1,
  class: "opacity-40"
};
const _hoisted_7$3 = {
  key: 0,
  class: "absolute z-30 mt-2 w-72 rounded-md border border-base-300 bg-base-100 shadow-lg p-3 flex flex-col gap-3"
};
const _hoisted_8$3 = { class: "grid grid-cols-6 gap-1" };
const _hoisted_9$2 = ["onClick"];
const _hoisted_10$1 = { class: "flex items-center gap-2" };
const _hoisted_11$1 = ["disabled"];
const _sfc_main$8 = /* @__PURE__ */ __mf_93({
  __name: "VEmojiPicker",
  props: {
    modelValue: {},
    disabled: { type: Boolean, default: false },
    label: {},
    placeholder: { default: "💬" }
  },
  emits: ["update:modelValue"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const open = __mf_45(false);
    const custom = __mf_45("");
    const EMOJIS = [
      "💡",
      "📝",
      "✏️",
      "📌",
      "📋",
      "🧩",
      "💻",
      "🛠️",
      "⚙️",
      "🔧",
      "🧪",
      "🚀",
      "🐛",
      "🩹",
      "🔍",
      "🧠",
      "📊",
      "📈",
      "🎨",
      "🖼️",
      "🧵",
      "🗂️",
      "📚",
      "📦",
      "🤖",
      "🦄",
      "🌱",
      "🔥",
      "⭐",
      "✅",
      "⚠️",
      "❓",
      "💬",
      "🗒️",
      "📢",
      "🎯"
    ];
    function pick(emoji) {
      if (props.disabled) return;
      emit("update:modelValue", emoji);
      open.value = false;
    }
    function clear() {
      if (props.disabled) return;
      emit("update:modelValue", null);
      open.value = false;
    }
    function applyCustom() {
      if (props.disabled) return;
      const trimmed = custom.value.trim();
      if (!trimmed) return;
      let value = trimmed;
      if (typeof Intl !== "undefined" && typeof Intl.Segmenter === "function") {
        const segmenter = new Intl.Segmenter(void 0, { granularity: "grapheme" });
        const first = segmenter.segment(trimmed)[Symbol.iterator]().next();
        if (!first.done) value = first.value.segment;
      }
      emit("update:modelValue", value);
      custom.value = "";
      open.value = false;
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$8, [
        __props.label ? (__mf_132(), __mf_83("span", _hoisted_2$8, __mf_61(__props.label), 1)) : __mf_82("", true),
        __mf_84("div", _hoisted_3$8, [
          __mf_84("button", {
            type: "button",
            disabled: __props.disabled,
            class: __mf_58(["inline-flex items-center justify-center size-9 rounded-md border border-base-300 bg-base-100 hover:bg-base-200 text-2xl leading-none", __props.disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer"]),
            "aria-label": __props.modelValue ? `Emoji ${__props.modelValue}` : "Pick emoji",
            onClick: _cache[0] || (_cache[0] = ($event) => open.value = !open.value)
          }, [
            __props.modelValue ? (__mf_132(), __mf_83("span", _hoisted_5$7, __mf_61(__props.modelValue), 1)) : (__mf_132(), __mf_83("span", _hoisted_6$3, __mf_61(__props.placeholder), 1))
          ], 10, _hoisted_4$8),
          open.value ? (__mf_132(), __mf_83("div", _hoisted_7$3, [
            __mf_84("div", _hoisted_8$3, [
              (__mf_132(), __mf_83(__mf_69, null, __mf_138(EMOJIS, (emoji) => {
                return __mf_84("button", {
                  key: emoji,
                  type: "button",
                  class: __mf_58(["size-9 rounded hover:bg-base-200 text-xl leading-none", __props.modelValue === emoji ? "bg-base-200 ring-2 ring-primary" : ""]),
                  onClick: ($event) => pick(emoji)
                }, __mf_61(emoji), 11, _hoisted_9$2);
              }), 64))
            ]),
            __mf_84("div", _hoisted_10$1, [
              __mf_168(__mf_84("input", {
                "onUpdate:modelValue": _cache[1] || (_cache[1] = ($event) => custom.value = $event),
                type: "text",
                class: "input input-sm input-bordered flex-1 text-lg",
                placeholder: "🎲 …",
                maxlength: "8",
                onKeyup: __mf_23(applyCustom, ["enter"])
              }, null, 544), [
                [__mf_21, custom.value]
              ]),
              __mf_84("button", {
                type: "button",
                class: "btn btn-xs",
                disabled: !custom.value.trim(),
                onClick: applyCustom
              }, " ✓ ", 8, _hoisted_11$1)
            ]),
            __props.modelValue ? (__mf_132(), __mf_83("button", {
              key: 0,
              type: "button",
              class: "btn btn-xs btn-ghost self-start",
              onClick: clear
            }, " × ")) : __mf_82("", true)
          ])) : __mf_82("", true)
        ])
      ]);
    };
  }
});

const _hoisted_1$7 = { class: "flex flex-col items-center justify-center text-center py-12 gap-3" };
const _hoisted_2$7 = {
  key: 0,
  class: "text-4xl opacity-60"
};
const _hoisted_3$7 = { class: "text-lg font-semibold" };
const _hoisted_4$7 = {
  key: 1,
  class: "text-sm opacity-70 max-w-md"
};
const _hoisted_5$6 = {
  key: 2,
  class: "mt-2"
};
const _sfc_main$7 = /* @__PURE__ */ __mf_93({
  __name: "VEmptyState",
  props: {
    headline: {},
    body: {}
  },
  setup(__props) {
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$7, [
        _ctx.$slots.icon ? (__mf_132(), __mf_83("div", _hoisted_2$7, [
          __mf_139(_ctx.$slots, "icon")
        ])) : __mf_82("", true),
        __mf_84("h3", _hoisted_3$7, __mf_61(__props.headline), 1),
        __props.body ? (__mf_132(), __mf_83("p", _hoisted_4$7, __mf_61(__props.body), 1)) : __mf_82("", true),
        _ctx.$slots.action ? (__mf_132(), __mf_83("div", _hoisted_5$6, [
          __mf_139(_ctx.$slots, "action")
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$6 = { class: "form-control w-full" };
const _hoisted_2$6 = {
  key: 0,
  class: "label"
};
const _hoisted_3$6 = { class: "label-text" };
const _hoisted_4$6 = ["accept", "multiple", "required", "disabled"];
const _hoisted_5$5 = { class: "text-sm" };
const _hoisted_6$2 = {
  key: 0,
  class: "text-xs opacity-60 mt-1"
};
const _hoisted_7$2 = { class: "w-full flex flex-col gap-1.5 text-left" };
const _hoisted_8$2 = { class: "font-mono truncate flex-1" };
const _hoisted_9$1 = { class: "text-xs opacity-60 shrink-0" };
const _hoisted_10 = ["disabled", "onClick"];
const _hoisted_11 = { class: "mt-3 text-xs opacity-70" };
const _hoisted_12 = ["disabled"];
const _hoisted_13 = {
  key: 1,
  class: "label"
};
const _sfc_main$6 = /* @__PURE__ */ __mf_93({
  __name: "VFileInput",
  props: {
    modelValue: {},
    label: {},
    accept: {},
    multiple: { type: Boolean, default: false },
    help: {},
    error: {},
    required: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false }
  },
  emits: ["update:modelValue"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const inputRef = __mf_45(null);
    const dragActive = __mf_45(false);
    function setFiles(picked) {
      if (!picked) {
        emit("update:modelValue", []);
        return;
      }
      const incoming = Array.from(picked);
      if (!props.multiple) {
        emit("update:modelValue", incoming.slice(0, 1));
        return;
      }
      const merged = props.modelValue.slice();
      const fingerprint = (f) => `${f.name}|${f.size}|${f.lastModified}`;
      const seen = new Set(merged.map(fingerprint));
      for (const f of incoming) {
        const key = fingerprint(f);
        if (!seen.has(key)) {
          merged.push(f);
          seen.add(key);
        }
      }
      emit("update:modelValue", merged);
    }
    function onChange(event) {
      const input = event.target;
      setFiles(input.files);
      input.value = "";
    }
    function onDragEnter(event) {
      if (props.disabled) return;
      event.preventDefault();
      dragActive.value = true;
    }
    function onDragOver(event) {
      if (props.disabled) return;
      event.preventDefault();
      dragActive.value = true;
    }
    function onDragLeave(event) {
      const related = event.relatedTarget;
      if (related && event.currentTarget.contains(related)) return;
      dragActive.value = false;
    }
    function onDrop(event) {
      event.preventDefault();
      dragActive.value = false;
      if (props.disabled) return;
      setFiles(event.dataTransfer?.files ?? null);
    }
    function clearAll() {
      emit("update:modelValue", []);
      if (inputRef.value) inputRef.value.value = "";
    }
    function removeAt(index) {
      const next = props.modelValue.slice();
      next.splice(index, 1);
      emit("update:modelValue", next);
      if (next.length === 0 && inputRef.value) inputRef.value.value = "";
    }
    function formatSize(bytes) {
      if (bytes < 1024) return `${bytes} B`;
      if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
      return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$6, [
        __props.label ? (__mf_132(), __mf_83("div", _hoisted_2$6, [
          __mf_84("span", _hoisted_3$6, __mf_61(__props.label), 1)
        ])) : __mf_82("", true),
        __mf_84("label", {
          class: __mf_58([
            "flex flex-col items-center justify-center text-center",
            "border-2 border-dashed rounded-lg px-6 py-6 transition-colors",
            __props.disabled ? "opacity-60 cursor-not-allowed" : "cursor-pointer",
            dragActive.value ? "border-primary bg-primary/10" : __props.error ? "border-error" : "border-base-300",
            !__props.disabled && !dragActive.value ? "hover:border-primary hover:bg-base-200" : ""
          ]),
          onDragenter: onDragEnter,
          onDragover: onDragOver,
          onDragleave: onDragLeave,
          onDrop
        }, [
          __mf_84("input", {
            ref_key: "inputRef",
            ref: inputRef,
            type: "file",
            class: "hidden",
            accept: __props.accept,
            multiple: __props.multiple,
            required: __props.required,
            disabled: __props.disabled,
            onChange
          }, null, 40, _hoisted_4$6),
          __props.modelValue.length === 0 ? (__mf_132(), __mf_83(__mf_69, { key: 0 }, [
            _cache[1] || (_cache[1] = __mf_84("span", {
              class: "text-2xl mb-2 opacity-60",
              "aria-hidden": "true"
            }, "⬆", -1)),
            __mf_84("span", _hoisted_5$5, [
              __mf_90(__mf_61(__props.multiple ? "Drop files here, or" : "Drop a file here, or") + " ", 1),
              _cache[0] || (_cache[0] = __mf_84("span", { class: "link link-primary" }, "browse", -1))
            ]),
            __props.accept ? (__mf_132(), __mf_83("span", _hoisted_6$2, " Accepted: " + __mf_61(__props.accept), 1)) : __mf_82("", true)
          ], 64)) : (__mf_132(), __mf_83(__mf_69, { key: 1 }, [
            __mf_84("ul", _hoisted_7$2, [
              (__mf_132(true), __mf_83(__mf_69, null, __mf_138(__props.modelValue, (file, idx) => {
                return __mf_132(), __mf_83("li", {
                  key: `${file.name}-${idx}`,
                  class: "flex items-center gap-2 text-sm"
                }, [
                  _cache[2] || (_cache[2] = __mf_84("span", { "aria-hidden": "true" }, "📄", -1)),
                  __mf_84("span", _hoisted_8$2, __mf_61(file.name), 1),
                  __mf_84("span", _hoisted_9$1, __mf_61(formatSize(file.size)), 1),
                  __mf_84("button", {
                    type: "button",
                    class: "btn btn-ghost btn-xs",
                    disabled: __props.disabled,
                    "aria-label": "Remove file",
                    onClick: __mf_24(($event) => removeAt(idx), ["stop", "prevent"])
                  }, "✕", 8, _hoisted_10)
                ]);
              }), 128))
            ]),
            __mf_84("div", _hoisted_11, [
              __props.multiple ? (__mf_132(), __mf_83(__mf_69, { key: 0 }, [
                __mf_90(__mf_61(__props.modelValue.length) + " file" + __mf_61(__props.modelValue.length === 1 ? "" : "s") + " ready — ", 1),
                _cache[3] || (_cache[3] = __mf_84("span", { class: "link link-primary" }, "add more", -1)),
                _cache[4] || (_cache[4] = __mf_90(" · ", -1))
              ], 64)) : __mf_82("", true),
              __mf_84("button", {
                type: "button",
                class: "link link-error",
                disabled: __props.disabled,
                onClick: __mf_24(clearAll, ["stop", "prevent"])
              }, "clear", 8, _hoisted_12)
            ])
          ], 64))
        ], 34),
        __props.error || __props.help ? (__mf_132(), __mf_83("div", _hoisted_13, [
          __mf_84("span", {
            class: __mf_58(["label-text-alt", __props.error ? "text-error" : "opacity-70"])
          }, __mf_61(__props.error || __props.help), 3)
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$5 = { class: "form-control w-full" };
const _hoisted_2$5 = {
  key: 0,
  class: "label"
};
const _hoisted_3$5 = { class: "label-text" };
const _hoisted_4$5 = ["type", "value", "placeholder", "required", "disabled", "autocomplete"];
const _hoisted_5$4 = {
  key: 1,
  class: "label"
};
const _sfc_main$5 = /* @__PURE__ */ __mf_93({
  __name: "VInput",
  props: {
    modelValue: {},
    label: {},
    type: { default: "text" },
    placeholder: {},
    help: {},
    error: {},
    required: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    autocomplete: {}
  },
  emits: ["update:modelValue"],
  setup(__props) {
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("label", _hoisted_1$5, [
        __props.label ? (__mf_132(), __mf_83("div", _hoisted_2$5, [
          __mf_84("span", _hoisted_3$5, __mf_61(__props.label), 1)
        ])) : __mf_82("", true),
        __mf_84("input", {
          type: __props.type,
          value: __props.modelValue,
          placeholder: __props.placeholder,
          required: __props.required,
          disabled: __props.disabled,
          autocomplete: __props.autocomplete,
          class: __mf_58(["input", "input-bordered", "w-full", { "input-error": !!__props.error }]),
          onInput: _cache[0] || (_cache[0] = (e) => _ctx.$emit("update:modelValue", e.target.value))
        }, null, 42, _hoisted_4$5),
        __props.error || __props.help ? (__mf_132(), __mf_83("div", _hoisted_5$4, [
          __mf_84("span", {
            class: __mf_58(["label-text-alt", __props.error ? "text-error" : "opacity-70"])
          }, __mf_61(__props.error || __props.help), 3)
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$4 = { class: "modal-box max-w-2xl" };
const _hoisted_2$4 = {
  key: 0,
  class: "flex items-center justify-between mb-3"
};
const _hoisted_3$4 = { class: "text-lg font-semibold" };
const _hoisted_4$4 = {
  key: 1,
  class: "modal-action"
};
const _sfc_main$4 = /* @__PURE__ */ __mf_93({
  __name: "VModal",
  props: {
    modelValue: { type: Boolean },
    title: {},
    closeOnBackdrop: { type: Boolean, default: true }
  },
  emits: ["update:modelValue"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const dialog = __mf_45(null);
    __mf_161(() => props.modelValue, (open) => {
      const el = dialog.value;
      if (!el) return;
      if (open && !el.open) el.showModal();
      if (!open && el.open) el.close();
    });
    __mf_126(() => {
      const el = dialog.value;
      if (!el) return;
      if (props.modelValue && !el.open) el.showModal();
    });
    function onClose() {
      emit("update:modelValue", false);
    }
    function onBackdropClick(event) {
      if (!props.closeOnBackdrop) return;
      if (event.target === dialog.value) onClose();
    }
    __mf_130(() => {
      if (dialog.value?.open) dialog.value.close();
    });
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("dialog", {
        ref_key: "dialog",
        ref: dialog,
        class: "modal",
        onClose,
        onClick: onBackdropClick
      }, [
        __mf_84("div", _hoisted_1$4, [
          __props.title || _ctx.$slots.header ? (__mf_132(), __mf_83("header", _hoisted_2$4, [
            __mf_84("h3", _hoisted_3$4, [
              __mf_139(_ctx.$slots, "header", {}, () => [
                __mf_90(__mf_61(__props.title), 1)
              ])
            ]),
            __mf_84("button", {
              type: "button",
              class: "btn btn-sm btn-circle btn-ghost",
              "aria-label": "Close",
              onClick: onClose
            }, "✕")
          ])) : __mf_82("", true),
          __mf_84("div", null, [
            __mf_139(_ctx.$slots, "default")
          ]),
          _ctx.$slots.actions ? (__mf_132(), __mf_83("footer", _hoisted_4$4, [
            __mf_139(_ctx.$slots, "actions")
          ])) : __mf_82("", true)
        ])
      ], 544);
    };
  }
});

const _hoisted_1$3 = { class: "flex items-center justify-between gap-3 text-sm" };
const _hoisted_2$3 = { class: "opacity-70" };
const _hoisted_3$3 = { class: "join" };
const _hoisted_4$3 = ["disabled"];
const _hoisted_5$3 = ["disabled"];
const _hoisted_6$1 = { class: "btn btn-sm btn-ghost join-item pointer-events-none" };
const _hoisted_7$1 = ["disabled"];
const _hoisted_8$1 = ["disabled"];
const _sfc_main$3 = /* @__PURE__ */ __mf_93({
  __name: "VPagination",
  props: {
    page: {},
    pageSize: {},
    totalCount: {}
  },
  emits: ["update:page"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const pageCount = __mf_80(() => {
      if (props.pageSize <= 0) return 1;
      return Math.max(1, Math.ceil(props.totalCount / props.pageSize));
    });
    const firstShownIndex = __mf_80(
      () => props.totalCount === 0 ? 0 : props.page * props.pageSize + 1
    );
    const lastShownIndex = __mf_80(
      () => Math.min(props.totalCount, (props.page + 1) * props.pageSize)
    );
    function setPage(p) {
      const clamped = Math.max(0, Math.min(p, pageCount.value - 1));
      if (clamped !== props.page) emit("update:page", clamped);
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$3, [
        __mf_84("span", _hoisted_2$3, [
          __props.totalCount === 0 ? (__mf_132(), __mf_83(__mf_69, { key: 0 }, [
            __mf_90("No items")
          ], 64)) : (__mf_132(), __mf_83(__mf_69, { key: 1 }, [
            __mf_90(__mf_61(firstShownIndex.value) + "–" + __mf_61(lastShownIndex.value) + " of " + __mf_61(__props.totalCount), 1)
          ], 64))
        ]),
        __mf_84("div", _hoisted_3$3, [
          __mf_84("button", {
            type: "button",
            class: "btn btn-sm btn-ghost join-item",
            disabled: __props.page <= 0,
            onClick: _cache[0] || (_cache[0] = ($event) => setPage(0))
          }, "«", 8, _hoisted_4$3),
          __mf_84("button", {
            type: "button",
            class: "btn btn-sm btn-ghost join-item",
            disabled: __props.page <= 0,
            onClick: _cache[1] || (_cache[1] = ($event) => setPage(__props.page - 1))
          }, "‹", 8, _hoisted_5$3),
          __mf_84("span", _hoisted_6$1, __mf_61(__props.page + 1) + " / " + __mf_61(pageCount.value), 1),
          __mf_84("button", {
            type: "button",
            class: "btn btn-sm btn-ghost join-item",
            disabled: __props.page >= pageCount.value - 1,
            onClick: _cache[2] || (_cache[2] = ($event) => setPage(__props.page + 1))
          }, "›", 8, _hoisted_7$1),
          __mf_84("button", {
            type: "button",
            class: "btn btn-sm btn-ghost join-item",
            disabled: __props.page >= pageCount.value - 1,
            onClick: _cache[3] || (_cache[3] = ($event) => setPage(pageCount.value - 1))
          }, "»", 8, _hoisted_8$1)
        ])
      ]);
    };
  }
});

const _hoisted_1$2 = { class: "form-control w-full" };
const _hoisted_2$2 = {
  key: 0,
  class: "label"
};
const _hoisted_3$2 = { class: "label-text" };
const _hoisted_4$2 = ["value", "disabled"];
const _hoisted_5$2 = {
  key: 0,
  value: "",
  disabled: ""
};
const _hoisted_6 = ["label"];
const _hoisted_7 = ["value", "disabled"];
const _hoisted_8 = ["value", "disabled"];
const _hoisted_9 = {
  key: 1,
  class: "label"
};
const _sfc_main$2 = /* @__PURE__ */ __mf_93({
  __name: "VSelect",
  props: {
    modelValue: {},
    options: {},
    label: {},
    placeholder: {},
    help: {},
    error: {},
    disabled: { type: Boolean, default: false }
  },
  emits: ["update:modelValue"],
  setup(__props, { emit: __emit }) {
    const emit = __emit;
    function onChange(event) {
      const raw = event.target.value;
      if (raw === "") {
        emit("update:modelValue", null);
        return;
      }
      emit("update:modelValue", raw);
    }
    function groupedOptions(options) {
      const sections = [];
      for (const opt of options) {
        const group = opt.group ?? null;
        const last = sections[sections.length - 1];
        if (last && last.group === group) {
          last.options.push(opt);
        } else {
          sections.push({ group, options: [opt] });
        }
      }
      return sections;
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("label", _hoisted_1$2, [
        __props.label ? (__mf_132(), __mf_83("div", _hoisted_2$2, [
          __mf_84("span", _hoisted_3$2, __mf_61(__props.label), 1)
        ])) : __mf_82("", true),
        __mf_84("select", {
          value: __props.modelValue ?? "",
          disabled: __props.disabled,
          class: __mf_58(["select", "select-bordered", "w-full", { "select-error": !!__props.error }]),
          onChange
        }, [
          __props.placeholder ? (__mf_132(), __mf_83("option", _hoisted_5$2, __mf_61(__props.placeholder), 1)) : __mf_82("", true),
          (__mf_132(true), __mf_83(__mf_69, null, __mf_138(groupedOptions(__props.options), (section, idx) => {
            return __mf_132(), __mf_83(__mf_69, { key: idx }, [
              section.group ? (__mf_132(), __mf_83("optgroup", {
                key: 0,
                label: section.group
              }, [
                (__mf_132(true), __mf_83(__mf_69, null, __mf_138(section.options, (opt) => {
                  return __mf_132(), __mf_83("option", {
                    key: String(opt.value),
                    value: opt.value,
                    disabled: opt.disabled
                  }, __mf_61(opt.label), 9, _hoisted_7);
                }), 128))
              ], 8, _hoisted_6)) : __mf_82("", true),
              (__mf_132(true), __mf_83(__mf_69, null, __mf_138(section.group ? [] : section.options, (opt) => {
                return __mf_132(), __mf_83("option", {
                  key: String(opt.value),
                  value: opt.value,
                  disabled: opt.disabled
                }, __mf_61(opt.label), 9, _hoisted_8);
              }), 128))
            ], 64);
          }), 128))
        ], 42, _hoisted_4$2),
        __props.error || __props.help ? (__mf_132(), __mf_83("div", _hoisted_9, [
          __mf_84("span", {
            class: __mf_58(["label-text-alt", __props.error ? "text-error" : "opacity-70"])
          }, __mf_61(__props.error || __props.help), 3)
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$1 = { class: "flex flex-col gap-2" };
const _hoisted_2$1 = {
  key: 0,
  class: "text-xs opacity-70"
};
const _hoisted_3$1 = ["aria-label", "onClick"];
const _hoisted_4$1 = ["placeholder", "disabled", "maxlength"];
const _hoisted_5$1 = {
  key: 1,
  class: "text-[10px] opacity-60"
};
const _sfc_main$1 = /* @__PURE__ */ __mf_93({
  __name: "VTagEditor",
  props: {
    modelValue: {},
    disabled: { type: Boolean, default: false },
    label: {},
    placeholder: {},
    maxTags: { default: 20 },
    maxTagChars: { default: 50 }
  },
  emits: ["update:modelValue"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const draft = __mf_45("");
    function normalise(raw) {
      return raw.trim().toLowerCase().slice(0, props.maxTagChars);
    }
    function commitDraft() {
      if (props.disabled) return;
      const value = normalise(draft.value);
      draft.value = "";
      if (!value) return;
      if (props.modelValue.includes(value)) return;
      if (props.modelValue.length >= props.maxTags) return;
      emit("update:modelValue", [...props.modelValue, value]);
    }
    function remove(tag) {
      if (props.disabled) return;
      emit("update:modelValue", props.modelValue.filter((t) => t !== tag));
    }
    function onKey(event) {
      if (event.key === "Enter" || event.key === "," || event.key === "Tab") {
        if (draft.value.trim()) {
          event.preventDefault();
          commitDraft();
        }
      } else if (event.key === "Backspace" && draft.value === "" && props.modelValue.length > 0) {
        event.preventDefault();
        const last = props.modelValue[props.modelValue.length - 1];
        remove(last);
      }
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$1, [
        __props.label ? (__mf_132(), __mf_83("span", _hoisted_2$1, __mf_61(__props.label), 1)) : __mf_82("", true),
        __mf_84("div", {
          class: __mf_58(["flex flex-wrap items-center gap-1 rounded-md border border-base-300 bg-base-100 px-2 py-1.5 min-h-[2.25rem]", __props.disabled ? "opacity-60" : ""])
        }, [
          (__mf_132(true), __mf_83(__mf_69, null, __mf_138(__props.modelValue, (tag) => {
            return __mf_132(), __mf_83("span", {
              key: tag,
              class: "inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded bg-base-200"
            }, [
              __mf_90(__mf_61(tag) + " ", 1),
              !__props.disabled ? (__mf_132(), __mf_83("button", {
                key: 0,
                type: "button",
                class: "opacity-60 hover:opacity-100",
                "aria-label": `Remove ${tag}`,
                onClick: ($event) => remove(tag)
              }, "×", 8, _hoisted_3$1)) : __mf_82("", true)
            ]);
          }), 128)),
          __mf_168(__mf_84("input", {
            "onUpdate:modelValue": _cache[0] || (_cache[0] = ($event) => draft.value = $event),
            type: "text",
            class: "flex-1 min-w-[6rem] bg-transparent outline-none text-sm py-0.5",
            placeholder: __props.placeholder ?? "",
            disabled: __props.disabled || __props.modelValue.length >= __props.maxTags,
            maxlength: __props.maxTagChars,
            onKeydown: onKey,
            onBlur: commitDraft
          }, null, 40, _hoisted_4$1), [
            [__mf_21, draft.value]
          ])
        ], 2),
        __props.modelValue.length >= __props.maxTags ? (__mf_132(), __mf_83("span", _hoisted_5$1, __mf_61(__props.modelValue.length) + " / " + __mf_61(__props.maxTags), 1)) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1 = { class: "form-control w-full" };
const _hoisted_2 = {
  key: 0,
  class: "label"
};
const _hoisted_3 = { class: "label-text" };
const _hoisted_4 = ["value", "placeholder", "rows", "required", "disabled"];
const _hoisted_5 = {
  key: 1,
  class: "label"
};
const _sfc_main = /* @__PURE__ */ __mf_93({
  __name: "VTextarea",
  props: {
    modelValue: {},
    label: {},
    placeholder: {},
    help: {},
    error: {},
    rows: { default: 8 },
    required: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false }
  },
  emits: ["update:modelValue"],
  setup(__props) {
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("label", _hoisted_1, [
        __props.label ? (__mf_132(), __mf_83("div", _hoisted_2, [
          __mf_84("span", _hoisted_3, __mf_61(__props.label), 1)
        ])) : __mf_82("", true),
        __mf_84("textarea", {
          value: __props.modelValue,
          placeholder: __props.placeholder,
          rows: __props.rows,
          required: __props.required,
          disabled: __props.disabled,
          class: __mf_58(["textarea", "textarea-bordered", "w-full", "font-mono", { "textarea-error": !!__props.error }]),
          onInput: _cache[0] || (_cache[0] = (e) => _ctx.$emit("update:modelValue", e.target.value))
        }, null, 42, _hoisted_4),
        __props.error || __props.help ? (__mf_132(), __mf_83("div", _hoisted_5, [
          __mf_84("span", {
            class: __mf_58(["label-text-alt", __props.error ? "text-error" : "opacity-70"])
          }, __mf_61(__props.error || __props.help), 3)
        ])) : __mf_82("", true)
      ]);
    };
  }
});

export { _sfc_main$f as VAlert, _sfc_main$e as VBackButton, _sfc_main$d as VButton, _sfc_main$c as VCard, _sfc_main$b as VCheckbox, _sfc_main$a as VColorPicker, _sfc_main$9 as VDataList, _sfc_main$8 as VEmojiPicker, _sfc_main$7 as VEmptyState, _sfc_main$6 as VFileInput, _sfc_main$5 as VInput, _sfc_main$4 as VModal, _sfc_main$3 as VPagination, _sfc_main$2 as VSelect, _sfc_main$1 as VTagEditor, _sfc_main as VTextarea };
