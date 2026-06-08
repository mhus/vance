import { k as __mf_93, f as __mf_80, q as __mf_132, r as __mf_83, s as __mf_58, A as __mf_139, w as __mf_82, t as __mf_84, u as __mf_61, e as __mf_45, h as __mf_161, i as __mf_126, j as __mf_130, B as __mf_90, o as __mf_69, x as __mf_138, C as __mf_168, D as __mf_21, m as __mf_91, v as __mf_55, E as __mf_166, F as __mf_81, z as __mf_24 } from './_virtual_mf___mfe_internal__vance_addon_calendar__loadShare__vue__loadShare__.mjs-DvmOVNPO.js';
import { b9 as mermaid_default } from './CodeEditor.vue_vue_type_style_index_0_scoped_d90b2dcd_lang-B0SRY8Hb.js';
import './js-yaml-K7iB6vJi.js';
import './preload-helper-C6a2snJ8.js';

const _sfc_main$a = /* @__PURE__ */ __mf_93({
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

const _hoisted_1$9 = ["href"];
const _hoisted_2$9 = {
  key: 0,
  class: "loading loading-spinner loading-sm"
};
const _hoisted_3$9 = ["type", "disabled"];
const _hoisted_4$9 = {
  key: 0,
  class: "loading loading-spinner loading-sm"
};
const _sfc_main$9 = /* @__PURE__ */ __mf_93({
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
        __props.loading ? (__mf_132(), __mf_83("span", _hoisted_2$9)) : __mf_82("", true),
        __mf_139(_ctx.$slots, "default")
      ], 10, _hoisted_1$9)) : (__mf_132(), __mf_83("button", {
        key: 1,
        type: __props.type,
        disabled: __props.disabled || __props.loading,
        class: __mf_58(["btn", variantClass.value, sizeClass.value, { "btn-block": __props.block }]),
        onClick: _cache[1] || (_cache[1] = (e) => _ctx.$emit("click", e))
      }, [
        __props.loading ? (__mf_132(), __mf_83("span", _hoisted_4$9)) : __mf_82("", true),
        __mf_139(_ctx.$slots, "default")
      ], 10, _hoisted_3$9));
    };
  }
});

const _hoisted_1$8 = { class: "form-control" };
const _hoisted_2$8 = { class: "cursor-pointer label justify-start gap-2 py-1" };
const _hoisted_3$8 = ["checked", "disabled"];
const _hoisted_4$8 = {
  key: 0,
  class: "label-text"
};
const _hoisted_5$7 = {
  key: 0,
  class: "text-xs opacity-70 mt-1"
};
const _sfc_main$8 = /* @__PURE__ */ __mf_93({
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
      return __mf_132(), __mf_83("label", _hoisted_1$8, [
        __mf_84("span", _hoisted_2$8, [
          __mf_84("input", {
            type: "checkbox",
            class: "checkbox checkbox-sm",
            checked: __props.modelValue,
            disabled: __props.disabled,
            onChange
          }, null, 40, _hoisted_3$8),
          __props.label ? (__mf_132(), __mf_83("span", _hoisted_4$8, __mf_61(__props.label), 1)) : __mf_82("", true)
        ]),
        __props.help ? (__mf_132(), __mf_83("span", _hoisted_5$7, __mf_61(__props.help), 1)) : __mf_82("", true)
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
const _hoisted_4$6 = ["type", "value", "placeholder", "required", "disabled", "autocomplete"];
const _hoisted_5$5 = {
  key: 1,
  class: "label"
};
const _sfc_main$6 = /* @__PURE__ */ __mf_93({
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
      return __mf_132(), __mf_83("label", _hoisted_1$6, [
        __props.label ? (__mf_132(), __mf_83("div", _hoisted_2$6, [
          __mf_84("span", _hoisted_3$6, __mf_61(__props.label), 1)
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
        }, null, 42, _hoisted_4$6),
        __props.error || __props.help ? (__mf_132(), __mf_83("div", _hoisted_5$5, [
          __mf_84("span", {
            class: __mf_58(["label-text-alt", __props.error ? "text-error" : "opacity-70"])
          }, __mf_61(__props.error || __props.help), 3)
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$5 = { class: "modal-box max-w-2xl" };
const _hoisted_2$5 = {
  key: 0,
  class: "flex items-center justify-between mb-3"
};
const _hoisted_3$5 = { class: "text-lg font-semibold" };
const _hoisted_4$5 = {
  key: 1,
  class: "modal-action"
};
const _sfc_main$5 = /* @__PURE__ */ __mf_93({
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
        __mf_84("div", _hoisted_1$5, [
          __props.title || _ctx.$slots.header ? (__mf_132(), __mf_83("header", _hoisted_2$5, [
            __mf_84("h3", _hoisted_3$5, [
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
          _ctx.$slots.actions ? (__mf_132(), __mf_83("footer", _hoisted_4$5, [
            __mf_139(_ctx.$slots, "actions")
          ])) : __mf_82("", true)
        ])
      ], 544);
    };
  }
});

const _hoisted_1$4 = { class: "form-control w-full" };
const _hoisted_2$4 = {
  key: 0,
  class: "label"
};
const _hoisted_3$4 = { class: "label-text" };
const _hoisted_4$4 = ["value", "disabled"];
const _hoisted_5$4 = {
  key: 0,
  value: "",
  disabled: ""
};
const _hoisted_6$2 = ["label"];
const _hoisted_7$2 = ["value", "disabled"];
const _hoisted_8$2 = ["value", "disabled"];
const _hoisted_9$2 = {
  key: 1,
  class: "label"
};
const _sfc_main$4 = /* @__PURE__ */ __mf_93({
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
      return __mf_132(), __mf_83("label", _hoisted_1$4, [
        __props.label ? (__mf_132(), __mf_83("div", _hoisted_2$4, [
          __mf_84("span", _hoisted_3$4, __mf_61(__props.label), 1)
        ])) : __mf_82("", true),
        __mf_84("select", {
          value: __props.modelValue ?? "",
          disabled: __props.disabled,
          class: __mf_58(["select", "select-bordered", "w-full", { "select-error": !!__props.error }]),
          onChange
        }, [
          __props.placeholder ? (__mf_132(), __mf_83("option", _hoisted_5$4, __mf_61(__props.placeholder), 1)) : __mf_82("", true),
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
                  }, __mf_61(opt.label), 9, _hoisted_7$2);
                }), 128))
              ], 8, _hoisted_6$2)) : __mf_82("", true),
              (__mf_132(true), __mf_83(__mf_69, null, __mf_138(section.group ? [] : section.options, (opt) => {
                return __mf_132(), __mf_83("option", {
                  key: String(opt.value),
                  value: opt.value,
                  disabled: opt.disabled
                }, __mf_61(opt.label), 9, _hoisted_8$2);
              }), 128))
            ], 64);
          }), 128))
        ], 42, _hoisted_4$4),
        __props.error || __props.help ? (__mf_132(), __mf_83("div", _hoisted_9$2, [
          __mf_84("span", {
            class: __mf_58(["label-text-alt", __props.error ? "text-error" : "opacity-70"])
          }, __mf_61(__props.error || __props.help), 3)
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$3 = { class: "flex flex-col gap-2" };
const _hoisted_2$3 = {
  key: 0,
  class: "text-xs opacity-70"
};
const _hoisted_3$3 = ["aria-label", "onClick"];
const _hoisted_4$3 = ["placeholder", "disabled", "maxlength"];
const _hoisted_5$3 = {
  key: 1,
  class: "text-[10px] opacity-60"
};
const _sfc_main$3 = /* @__PURE__ */ __mf_93({
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
      return __mf_132(), __mf_83("div", _hoisted_1$3, [
        __props.label ? (__mf_132(), __mf_83("span", _hoisted_2$3, __mf_61(__props.label), 1)) : __mf_82("", true),
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
              }, "×", 8, _hoisted_3$3)) : __mf_82("", true)
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
          }, null, 40, _hoisted_4$3), [
            [__mf_21, draft.value]
          ])
        ], 2),
        __props.modelValue.length >= __props.maxTags ? (__mf_132(), __mf_83("span", _hoisted_5$3, __mf_61(__props.modelValue.length) + " / " + __mf_61(__props.maxTags), 1)) : __mf_82("", true)
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
const _hoisted_4$2 = ["value", "placeholder", "rows", "required", "disabled"];
const _hoisted_5$2 = {
  key: 1,
  class: "label"
};
const _sfc_main$2 = /* @__PURE__ */ __mf_93({
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
      return __mf_132(), __mf_83("label", _hoisted_1$2, [
        __props.label ? (__mf_132(), __mf_83("div", _hoisted_2$2, [
          __mf_84("span", _hoisted_3$2, __mf_61(__props.label), 1)
        ])) : __mf_82("", true),
        __mf_84("textarea", {
          value: __props.modelValue,
          placeholder: __props.placeholder,
          rows: __props.rows,
          required: __props.required,
          disabled: __props.disabled,
          class: __mf_58(["textarea", "textarea-bordered", "w-full", "font-mono", { "textarea-error": !!__props.error }]),
          onInput: _cache[0] || (_cache[0] = (e) => _ctx.$emit("update:modelValue", e.target.value))
        }, null, 42, _hoisted_4$2),
        __props.error || __props.help ? (__mf_132(), __mf_83("div", _hoisted_5$2, [
          __mf_84("span", {
            class: __mf_58(["label-text-alt", __props.error ? "text-error" : "opacity-70"])
          }, __mf_61(__props.error || __props.help), 3)
        ])) : __mf_82("", true)
      ]);
    };
  }
});

const _hoisted_1$1 = { class: "flex flex-col h-full" };
const _hoisted_2$1 = { class: "flex items-center justify-between p-4 border-b border-base-300" };
const _hoisted_3$1 = { class: "flex-1 overflow-y-auto p-4 flex flex-col gap-3" };
const _hoisted_4$1 = { class: "grid grid-cols-2 gap-2" };
const _hoisted_5$1 = { class: "flex gap-2 mt-2" };
const _hoisted_6$1 = ["href"];
const _hoisted_7$1 = ["href"];
const _hoisted_8$1 = { class: "flex items-center justify-between p-4 border-t border-base-300" };
const _hoisted_9$1 = { class: "flex gap-2" };
const _sfc_main$1 = /* @__PURE__ */ __mf_93({
  __name: "CalendarEventDetail",
  props: {
    event: {},
    lanes: {}
  },
  emits: ["close", "update", "delete"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const title = __mf_45(props.event.title);
    const start = __mf_45(props.event.start);
    const end = __mf_45(props.event.end ?? "");
    const allDay = __mf_45(props.event.allDay);
    const location = __mf_45(props.event.location ?? "");
    const attendees = __mf_45([...props.event.attendees]);
    const recurrence = __mf_45(props.event.recurrence ?? "");
    const tags = __mf_45([...props.event.tags]);
    const notes = __mf_45(props.event.notes ?? "");
    const targetLane = __mf_45(props.event.lane);
    __mf_161(
      () => props.event.id,
      () => {
        title.value = props.event.title;
        start.value = props.event.start;
        end.value = props.event.end ?? "";
        allDay.value = props.event.allDay;
        location.value = props.event.location ?? "";
        attendees.value = [...props.event.attendees];
        recurrence.value = props.event.recurrence ?? "";
        tags.value = [...props.event.tags];
        notes.value = props.event.notes ?? "";
        targetLane.value = props.event.lane;
      }
    );
    const dirty = __mf_80(
      () => title.value !== props.event.title || start.value !== props.event.start || end.value !== (props.event.end ?? "") || allDay.value !== props.event.allDay || location.value !== (props.event.location ?? "") || !arraysEqual(attendees.value, props.event.attendees) || recurrence.value !== (props.event.recurrence ?? "") || !arraysEqual(tags.value, props.event.tags) || notes.value !== (props.event.notes ?? "") || targetLane.value !== props.event.lane
    );
    function arraysEqual(a, b) {
      if (a.length !== b.length) return false;
      for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
      return true;
    }
    function save() {
      const patch = {};
      if (title.value !== props.event.title) patch.title = title.value;
      if (start.value !== props.event.start) patch.start = start.value;
      if (end.value !== (props.event.end ?? "")) patch.end = end.value;
      if (allDay.value !== props.event.allDay) patch.allDay = allDay.value;
      if (location.value !== (props.event.location ?? "")) patch.location = location.value;
      if (!arraysEqual(attendees.value, props.event.attendees)) patch.attendees = attendees.value;
      if (recurrence.value !== (props.event.recurrence ?? "")) patch.recurrence = recurrence.value;
      if (!arraysEqual(tags.value, props.event.tags)) patch.tags = tags.value;
      if (notes.value !== (props.event.notes ?? "")) patch.notes = notes.value;
      if (targetLane.value !== props.event.lane) patch.targetLane = targetLane.value;
      emit("update", patch);
    }
    function confirmDelete() {
      if (window.confirm(`Delete event "${props.event.title}"?`)) emit("delete");
    }
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1$1, [
        __mf_84("div", _hoisted_2$1, [
          _cache[12] || (_cache[12] = __mf_84("h2", { class: "text-lg font-semibold" }, "Event detail", -1)),
          __mf_84("button", {
            class: "text-base-content/60 hover:text-base-content text-xl leading-none",
            onClick: _cache[0] || (_cache[0] = ($event) => emit("close"))
          }, "×")
        ]),
        __mf_84("div", _hoisted_3$1, [
          __mf_91(__mf_55(_sfc_main$6), {
            modelValue: title.value,
            "onUpdate:modelValue": _cache[1] || (_cache[1] = ($event) => title.value = $event),
            label: "Title"
          }, null, 8, ["modelValue"]),
          __mf_84("div", _hoisted_4$1, [
            __mf_91(__mf_55(_sfc_main$6), {
              modelValue: start.value,
              "onUpdate:modelValue": _cache[2] || (_cache[2] = ($event) => start.value = $event),
              label: "Start",
              placeholder: "YYYY-MM-DD[THH:mm]"
            }, null, 8, ["modelValue"]),
            __mf_91(__mf_55(_sfc_main$6), {
              modelValue: end.value,
              "onUpdate:modelValue": _cache[3] || (_cache[3] = ($event) => end.value = $event),
              label: "End",
              placeholder: "(optional)"
            }, null, 8, ["modelValue"])
          ]),
          __mf_91(__mf_55(_sfc_main$8), {
            modelValue: allDay.value,
            "onUpdate:modelValue": _cache[4] || (_cache[4] = ($event) => allDay.value = $event),
            label: "All-day event"
          }, null, 8, ["modelValue"]),
          __mf_91(__mf_55(_sfc_main$4), {
            "model-value": targetLane.value,
            label: "Lane",
            options: __props.lanes.map((l) => ({ value: l.name, label: l.title ?? l.name })),
            "onUpdate:modelValue": _cache[5] || (_cache[5] = (v) => targetLane.value = v ?? props.event.lane)
          }, null, 8, ["model-value", "options"]),
          __mf_91(__mf_55(_sfc_main$6), {
            modelValue: location.value,
            "onUpdate:modelValue": _cache[6] || (_cache[6] = ($event) => location.value = $event),
            label: "Location"
          }, null, 8, ["modelValue"]),
          __mf_91(__mf_55(_sfc_main$3), {
            modelValue: attendees.value,
            "onUpdate:modelValue": _cache[7] || (_cache[7] = ($event) => attendees.value = $event),
            label: "Attendees"
          }, null, 8, ["modelValue"]),
          __mf_91(__mf_55(_sfc_main$6), {
            modelValue: recurrence.value,
            "onUpdate:modelValue": _cache[8] || (_cache[8] = ($event) => recurrence.value = $event),
            label: "Recurrence (RRULE)",
            placeholder: "FREQ=WEEKLY;BYDAY=MO,…"
          }, null, 8, ["modelValue"]),
          __mf_91(__mf_55(_sfc_main$3), {
            modelValue: tags.value,
            "onUpdate:modelValue": _cache[9] || (_cache[9] = ($event) => tags.value = $event),
            label: "Tags"
          }, null, 8, ["modelValue"]),
          __mf_91(__mf_55(_sfc_main$2), {
            modelValue: notes.value,
            "onUpdate:modelValue": _cache[10] || (_cache[10] = ($event) => notes.value = $event),
            label: "Notes",
            rows: 4
          }, null, 8, ["modelValue"]),
          __mf_84("div", _hoisted_5$1, [
            __props.event.googleUrl ? (__mf_132(), __mf_83("a", {
              key: 0,
              href: __props.event.googleUrl,
              target: "_blank",
              rel: "noopener",
              class: "flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2"
            }, "Add to Google", 8, _hoisted_6$1)) : __mf_82("", true),
            __props.event.outlookUrl ? (__mf_132(), __mf_83("a", {
              key: 1,
              href: __props.event.outlookUrl,
              target: "_blank",
              rel: "noopener",
              class: "flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2"
            }, "Add to Outlook", 8, _hoisted_7$1)) : __mf_82("", true)
          ]),
          __mf_91(__mf_55(_sfc_main$a), {
            variant: "info",
            class: "text-xs"
          }, {
            default: __mf_166(() => [
              __mf_90(" Source: " + __mf_61(__props.event.sourcePath), 1)
            ]),
            _: 1
          })
        ]),
        __mf_84("div", _hoisted_8$1, [
          __mf_91(__mf_55(_sfc_main$9), {
            variant: "ghost",
            class: "text-error",
            onClick: confirmDelete
          }, {
            default: __mf_166(() => [..._cache[13] || (_cache[13] = [
              __mf_90("Delete", -1)
            ])]),
            _: 1
          }),
          __mf_84("div", _hoisted_9$1, [
            __mf_91(__mf_55(_sfc_main$9), {
              variant: "ghost",
              disabled: !dirty.value,
              onClick: _cache[11] || (_cache[11] = ($event) => emit("close"))
            }, {
              default: __mf_166(() => [..._cache[14] || (_cache[14] = [
                __mf_90("Discard", -1)
              ])]),
              _: 1
            }, 8, ["disabled"]),
            __mf_91(__mf_55(_sfc_main$9), {
              variant: "primary",
              disabled: !dirty.value,
              onClick: save
            }, {
              default: __mf_166(() => [..._cache[15] || (_cache[15] = [
                __mf_90("Save", -1)
              ])]),
              _: 1
            }, 8, ["disabled"])
          ])
        ])
      ]);
    };
  }
});

const GLOBAL_KEY = '__VANCE_PLATFORM__';
function readBindings() {
    return globalThis[GLOBAL_KEY] ?? null;
}
function require_() {
    const bindings = readBindings();
    if (bindings === null) {
        throw new Error('@vance/shared: platform not configured — call configurePlatform({ storage, rest }) at app startup.');
    }
    return bindings;
}
/**
 * Resolve the host-provided storage bindings. Throws if
 * {@link configurePlatform} has not been called yet — there is no
 * sensible default, since the choice between `localStorage` and
 * `AsyncStorage` (and their secure counterparts) is the host's
 * responsibility.
 */
function getStorage() {
    return require_().storage;
}
/**
 * Resolve the host-provided REST configuration. Throws if
 * {@link configurePlatform} has not been called yet.
 */
function getRestConfig() {
    return require_().rest;
}

/**
 * Canonical key strings for the platform's {@link KeyValueStore}
 * bindings. All keys are prefixed `vance.` to avoid collisions with
 * other apps that share the storage namespace (Web `localStorage`,
 * Mobile `AsyncStorage`).
 *
 * Each key documents which store it belongs to:
 * - `secureStore`: tokens and other sensitive material
 * - `prefsStore`: UI preferences, identity hints, draft state
 *
 * Web collapses both stores onto `localStorage`, so the distinction
 * is non-load-bearing there; Mobile honours it (Keychain vs.
 * AsyncStorage).
 *
 * This module replaces the legacy `persistence/keys.ts`, which is
 * kept for backwards compatibility until Phase 4 of the
 * platform-neutrality refactor (see
 * `readme/reorg-webui-to-clean-shared.md`).
 */
const StorageKeys = {
    // ── secureStore ─────────────────────────────────────────────────
    /** Access JWT — Bearer-mode REST/WS authentication. Mobile only.
     *  Web cookie-mode never sees the token. */
    authAccessToken: 'vance.auth.accessToken',
    // ── prefsStore ──────────────────────────────────────────────────
    /** Tenant the user belongs to. Set after successful login on both
     *  Web (mirrored from the `vance_data` cookie) and Mobile (read
     *  from the login response body). */
    identityTenantId: 'vance.identity.tenantId'};

// Identity helpers read from the platform-bound prefsStore. The host
// is responsible for keeping these keys in sync with the authoritative
// source: Web copies them from the `vance_data` cookie at boot,
// Mobile writes them after a successful body-mode login.
//
// JavaScript never sees the access JWT on Web (HttpOnly cookie); on
// Mobile the bearer token lives in the platform's `secureStore`
// (a different store under the same {@link PlatformStorage} binding).
function getTenantId() {
    return getStorage().prefsStore.get(StorageKeys.identityTenantId);
}

class RestError extends Error {
    status;
    path;
    constructor(status, path, message) {
        super(message);
        this.status = status;
        this.path = path;
        this.name = 'RestError';
    }
}
/**
 * Resolve the Brain's base URL from the host-bound configuration.
 * The host calls {@link configurePlatform} once at boot with the
 * appropriate value (`''` for same-origin Web, an explicit origin
 * for Mobile or cross-origin dev). This module never inspects the
 * environment directly.
 */
function brainBaseUrl() {
    return getRestConfig().baseUrl;
}
/**
 * Tenant-scoped REST request. The `path` is appended to
 * `${baseUrl}/brain/{tenant}/`, so callers pass relative paths like
 * `'sessions'` or `'documents/abc'`.
 *
 * On `401` the helper attempts a single silent re-mint and retries
 * the original request once. If the retry also fails (or no refresh
 * is possible), it triggers the host's `onUnauthorized` callback.
 */
async function brainFetch(method, path, options = {}) {
    const tenant = getTenantId();
    if (!tenant)
        throw new RestError(0, path, 'No tenant configured — user is not logged in.');
    const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
    const response = await doFetch(url, method, options);
    if (response.status === 401 && options.authenticated !== false) {
        const refreshed = await getRestConfig().refreshAccess();
        if (refreshed) {
            const retry = await doFetch(url, method, options);
            if (retry.ok)
                return parseJson(retry);
        }
        redirectToLogin();
        return new Promise(() => { });
    }
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new RestError(response.status, path, text || response.statusText);
    }
    return parseJson(response);
}
async function doFetch(url, method, options) {
    const config = getRestConfig();
    const headers = { ...(options.headers ?? {}) };
    // FormData carries its own multipart boundary — let the host set
    // Content-Type so the boundary is correct, and never JSON-stringify it.
    const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
    if (options.body !== undefined && !isFormData) {
        headers['Content-Type'] = 'application/json';
    }
    if (config.authMode === 'bearer' && options.authenticated !== false) {
        const token = getStorage().secureStore.get(StorageKeys.authAccessToken);
        if (token !== null)
            headers['Authorization'] = `Bearer ${token}`;
    }
    let body;
    if (options.body !== undefined) {
        body = isFormData ? options.body : JSON.stringify(options.body);
    }
    return fetch(url, {
        method,
        headers,
        body,
        credentials: config.authMode === 'cookie' && options.authenticated !== false ? 'include' : 'omit',
    });
}
async function parseJson(response) {
    if (response.status === 204)
        return undefined;
    const contentType = response.headers.get('Content-Type') ?? '';
    if (!contentType.includes('application/json'))
        return undefined;
    return (await response.json());
}
function redirectToLogin() {
    getRestConfig().onUnauthorized();
}

function qs(params) {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}
async function getCalendarPlanner(projectId, folder) {
  return brainFetch(
    "GET",
    `calendar/planner?${qs({ projectId, folder })}`
  );
}
async function createCalendarEvent(projectId, folder, request) {
  return brainFetch(
    "POST",
    `calendar/events?${qs({ projectId, folder })}`,
    { body: request }
  );
}
async function updateCalendarEvent(projectId, folder, id, request) {
  return brainFetch(
    "PATCH",
    `calendar/events?${qs({ projectId, folder, id })}`,
    { body: request }
  );
}
async function deleteCalendarEvent(projectId, folder, id) {
  return brainFetch(
    "DELETE",
    `calendar/events?${qs({ projectId, folder, id })}`
  );
}
async function rebuildCalendarPlanner(projectId, folder) {
  return brainFetch(
    "POST",
    `calendar/rebuild?${qs({ projectId, folder })}`
  );
}

const _hoisted_1 = { class: "flex flex-col h-full" };
const _hoisted_2 = { class: "flex items-center justify-between p-4 border-b border-base-300" };
const _hoisted_3 = { class: "text-xl font-semibold" };
const _hoisted_4 = { class: "text-sm text-base-content/60 mt-0.5" };
const _hoisted_5 = { class: "flex gap-2 items-center" };
const _hoisted_6 = {
  key: 0,
  class: "text-sm text-base-content/60"
};
const _hoisted_7 = { class: "text-error" };
const _hoisted_8 = {
  key: 1,
  class: "p-8 text-base-content/70"
};
const _hoisted_9 = {
  key: 2,
  class: "flex-1 flex overflow-hidden"
};
const _hoisted_10 = { class: "w-56 flex-shrink-0 border-r border-base-300 bg-base-200/40 overflow-y-auto" };
const _hoisted_11 = { class: "border-t border-base-300 mt-1 pt-1" };
const _hoisted_12 = ["onClick"];
const _hoisted_13 = { class: "font-medium text-sm" };
const _hoisted_14 = {
  key: 0,
  class: "text-xs text-warning"
};
const _hoisted_15 = { class: "text-xs text-base-content/60" };
const _hoisted_16 = { class: "flex-1 overflow-y-auto" };
const _hoisted_17 = {
  key: 0,
  class: "p-4 flex flex-col gap-4"
};
const _hoisted_18 = { key: 0 };
const _hoisted_19 = ["innerHTML"];
const _hoisted_20 = { class: "text-lg font-semibold mb-2" };
const _hoisted_21 = {
  key: 0,
  class: "text-base-content/60 text-sm"
};
const _hoisted_22 = {
  key: 1,
  class: "border border-base-300 rounded overflow-hidden"
};
const _hoisted_23 = { class: "w-full text-sm" };
const _hoisted_24 = { class: "px-3 py-2" };
const _hoisted_25 = { class: "font-medium" };
const _hoisted_26 = { class: "text-xs text-base-content/60" };
const _hoisted_27 = { class: "px-3 py-2" };
const _hoisted_28 = { class: "font-medium" };
const _hoisted_29 = { class: "text-xs text-base-content/60" };
const _hoisted_30 = { class: "px-3 py-2 text-xs text-base-content/70" };
const _hoisted_31 = {
  key: 1,
  class: "flex flex-col h-full"
};
const _hoisted_32 = { class: "p-4 flex items-center justify-between border-b border-base-300" };
const _hoisted_33 = { class: "text-lg font-semibold" };
const _hoisted_34 = { class: "flex-1 overflow-y-auto p-4" };
const _hoisted_35 = {
  key: 1,
  class: "flex flex-col gap-2"
};
const _hoisted_36 = ["onClick"];
const _hoisted_37 = { class: "flex items-start justify-between gap-3" };
const _hoisted_38 = { class: "flex-1 min-w-0" };
const _hoisted_39 = { class: "font-medium" };
const _hoisted_40 = { class: "text-xs text-base-content/70 mt-0.5" };
const _hoisted_41 = {
  key: 0,
  class: "ml-1 text-base-content/50"
};
const _hoisted_42 = {
  key: 1,
  class: "ml-1 text-info"
};
const _hoisted_43 = { class: "flex gap-1" };
const _hoisted_44 = ["href"];
const _hoisted_45 = ["href"];
const _hoisted_46 = {
  key: 0,
  class: "text-xs text-base-content/60 mt-1"
};
const _hoisted_47 = {
  key: 1,
  class: "flex flex-wrap gap-1 mt-1"
};
const _hoisted_48 = { class: "flex flex-col gap-3" };
const _hoisted_49 = { class: "flex justify-end gap-2 pt-2" };
const OVERVIEW = "__overview__";
const _sfc_main = /* @__PURE__ */ __mf_93({
  __name: "CalendarPlanner",
  props: {
    projectId: {},
    folder: {},
    title: {}
  },
  setup(__props) {
    const props = __props;
    const planner = __mf_45(null);
    const loading = __mf_45(true);
    const error = __mf_45(null);
    const activeTab = __mf_45(OVERVIEW);
    const selectedEventId = __mf_45(null);
    const showCreateModal = __mf_45(false);
    const newEventForm = __mf_45({
      title: "",
      start: "",
      lane: "",
      allDay: false,
      attendees: [],
      tags: []
    });
    const ganttSvg = __mf_45("");
    const ganttError = __mf_45(null);
    let mermaidInitialized = false;
    const eventsForTab = __mf_80(() => {
      if (!planner.value || activeTab.value === OVERVIEW) return [];
      return planner.value.events.filter((e) => e.lane === activeTab.value).slice().sort((a, b) => a.start.localeCompare(b.start));
    });
    const selectedEvent = __mf_80(() => {
      if (!planner.value || !selectedEventId.value) return null;
      return planner.value.events.find((e) => e.id === selectedEventId.value) ?? null;
    });
    async function load() {
      loading.value = true;
      error.value = null;
      try {
        planner.value = await getCalendarPlanner(props.projectId, props.folder);
        if (activeTab.value !== OVERVIEW && !planner.value.lanes.some((l) => l.name === activeTab.value)) {
          activeTab.value = OVERVIEW;
        }
        await renderGantt();
      } catch (e) {
        error.value = `Could not load planner: ${e.message}`;
      } finally {
        loading.value = false;
      }
    }
    function initMermaid() {
      if (mermaidInitialized) return;
      mermaid_default.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: "default"
      });
      mermaidInitialized = true;
    }
    async function renderGantt() {
      if (!planner.value) return;
      const gantt = planner.value.artefacts.find((a) => a.name === "gantt");
      if (!gantt?.body) {
        ganttSvg.value = "";
        ganttError.value = null;
        return;
      }
      const source = extractMermaidFence(gantt.body);
      if (!source) {
        ganttSvg.value = "";
        ganttError.value = "Could not extract Mermaid source from _gantt.md.";
        return;
      }
      try {
        initMermaid();
        const id = `gantt-${Date.now()}`;
        const out = await mermaid_default.render(id, source);
        ganttSvg.value = out.svg;
        ganttError.value = null;
      } catch (e) {
        ganttSvg.value = "";
        ganttError.value = e.message;
      }
    }
    function extractMermaidFence(body) {
      const match = body.match(/```mermaid\s*\n([\s\S]*?)\n```/);
      return match ? match[1] : null;
    }
    async function rebuild() {
      try {
        await rebuildCalendarPlanner(props.projectId, props.folder);
        await load();
      } catch (e) {
        error.value = `Rebuild failed: ${e.message}`;
      }
    }
    function openCreateModal(lane) {
      newEventForm.value = {
        title: "",
        start: (/* @__PURE__ */ new Date()).toISOString().slice(0, 10),
        lane,
        allDay: false,
        attendees: [],
        tags: []
      };
      showCreateModal.value = true;
    }
    async function submitCreate() {
      if (!newEventForm.value.title.trim() || !newEventForm.value.start.trim()) return;
      try {
        const created = await createCalendarEvent(
          props.projectId,
          props.folder,
          newEventForm.value
        );
        if (planner.value) planner.value.events.push(created);
        showCreateModal.value = false;
        selectedEventId.value = created.id;
        activeTab.value = created.lane;
      } catch (e) {
        error.value = `Create failed: ${e.message}`;
      }
    }
    async function onEventUpdate(id, patch) {
      try {
        const updated = await updateCalendarEvent(props.projectId, props.folder, id, patch);
        if (!planner.value) return;
        const idx = planner.value.events.findIndex((e) => e.id === id);
        if (idx >= 0) planner.value.events[idx] = updated;
        selectedEventId.value = updated.id;
        if (patch.targetLane && updated.lane !== activeTab.value) {
          activeTab.value = updated.lane;
        }
      } catch (e) {
        error.value = `Update failed: ${e.message}`;
      }
    }
    async function onEventDelete(id) {
      try {
        await deleteCalendarEvent(props.projectId, props.folder, id);
        if (planner.value) {
          planner.value.events = planner.value.events.filter((e) => e.id !== id);
        }
        selectedEventId.value = null;
      } catch (e) {
        error.value = `Delete failed: ${e.message}`;
      }
    }
    __mf_161(activeTab, () => {
      if (activeTab.value === OVERVIEW) {
        void renderGantt();
      }
    });
    function formatDateRange(ev) {
      if (!ev.end) return ev.start;
      if (ev.allDay && ev.start === ev.end) return ev.start;
      return `${ev.start} → ${ev.end}`;
    }
    function rangeLabel(c) {
      return `${c.overlapStart.replace("T", " ")} – ${c.overlapEnd.replace("T", " ")}`;
    }
    __mf_126(load);
    return (_ctx, _cache) => {
      return __mf_132(), __mf_83("div", _hoisted_1, [
        __mf_84("div", _hoisted_2, [
          __mf_84("div", null, [
            __mf_84("h1", _hoisted_3, __mf_61(__props.title ?? __props.folder), 1),
            __mf_84("div", _hoisted_4, [
              __mf_90(__mf_61(__props.folder) + " ", 1),
              planner.value?.windowFrom || planner.value?.windowUntil ? (__mf_132(), __mf_83(__mf_69, { key: 0 }, [
                __mf_90(" · " + __mf_61(planner.value?.windowFrom ?? "?") + " → " + __mf_61(planner.value?.windowUntil ?? "?"), 1)
              ], 64)) : __mf_82("", true)
            ])
          ]),
          __mf_84("div", _hoisted_5, [
            planner.value ? (__mf_132(), __mf_83("span", _hoisted_6, [
              __mf_90(__mf_61(planner.value.events.length) + " events · " + __mf_61(planner.value.lanes.length) + " lanes ", 1),
              planner.value.conflicts.length > 0 ? (__mf_132(), __mf_83(__mf_69, { key: 0 }, [
                _cache[13] || (_cache[13] = __mf_90(" · ", -1)),
                __mf_84("span", _hoisted_7, "⚠ " + __mf_61(planner.value.conflicts.length) + " conflicts", 1)
              ], 64)) : __mf_82("", true)
            ])) : __mf_82("", true),
            __mf_91(__mf_55(_sfc_main$9), {
              size: "sm",
              variant: "ghost",
              onClick: load
            }, {
              default: __mf_166(() => [..._cache[14] || (_cache[14] = [
                __mf_90("Reload", -1)
              ])]),
              _: 1
            }),
            __mf_91(__mf_55(_sfc_main$9), {
              size: "sm",
              variant: "ghost",
              onClick: rebuild
            }, {
              default: __mf_166(() => [..._cache[15] || (_cache[15] = [
                __mf_90("Rebuild artefacts", -1)
              ])]),
              _: 1
            })
          ])
        ]),
        error.value ? (__mf_132(), __mf_81(__mf_55(_sfc_main$a), {
          key: 0,
          variant: "error",
          class: "m-4"
        }, {
          default: __mf_166(() => [
            __mf_90(__mf_61(error.value), 1)
          ]),
          _: 1
        })) : __mf_82("", true),
        loading.value ? (__mf_132(), __mf_83("div", _hoisted_8, "Loading planner…")) : (__mf_132(), __mf_83("div", _hoisted_9, [
          __mf_84("div", _hoisted_10, [
            __mf_84("button", {
              class: __mf_58(["w-full text-left px-4 py-3 hover:bg-base-200 transition-colors", activeTab.value === OVERVIEW ? "bg-base-100 border-l-4 border-primary" : ""]),
              onClick: _cache[0] || (_cache[0] = ($event) => {
                activeTab.value = OVERVIEW;
                selectedEventId.value = null;
              })
            }, [..._cache[16] || (_cache[16] = [
              __mf_84("div", { class: "font-medium" }, "Overview", -1),
              __mf_84("div", { class: "text-xs text-base-content/60 mt-0.5" }, "Gantt + conflicts", -1)
            ])], 2),
            __mf_84("div", _hoisted_11, [
              (__mf_132(true), __mf_83(__mf_69, null, __mf_138(planner.value?.lanes ?? [], (lane) => {
                return __mf_132(), __mf_83("button", {
                  key: lane.name,
                  class: __mf_58(["w-full text-left px-4 py-2 hover:bg-base-200 transition-colors flex items-center justify-between", activeTab.value === lane.name ? "bg-base-100 border-l-4 border-primary" : ""]),
                  onClick: ($event) => {
                    activeTab.value = lane.name;
                    selectedEventId.value = null;
                  }
                }, [
                  __mf_84("div", null, [
                    __mf_84("div", _hoisted_13, __mf_61(lane.title ?? lane.name), 1),
                    !lane.declared ? (__mf_132(), __mf_83("div", _hoisted_14, "⚠ undeclared")) : __mf_82("", true)
                  ]),
                  __mf_84("span", _hoisted_15, __mf_61(lane.eventCount), 1)
                ], 10, _hoisted_12);
              }), 128))
            ])
          ]),
          __mf_84("div", _hoisted_16, [
            activeTab.value === OVERVIEW ? (__mf_132(), __mf_83("div", _hoisted_17, [
              planner.value?.artefacts.find((a) => a.name === "gantt") ? (__mf_132(), __mf_83("section", _hoisted_18, [
                _cache[17] || (_cache[17] = __mf_84("h2", { class: "text-lg font-semibold mb-2" }, "Gantt", -1)),
                ganttError.value ? (__mf_132(), __mf_81(__mf_55(_sfc_main$a), {
                  key: 0,
                  variant: "error"
                }, {
                  default: __mf_166(() => [
                    __mf_90(" Could not render Gantt: " + __mf_61(ganttError.value), 1)
                  ]),
                  _: 1
                })) : ganttSvg.value ? (__mf_132(), __mf_83("div", {
                  key: 1,
                  class: "bg-base-100 border border-base-300 rounded p-4 overflow-x-auto",
                  innerHTML: ganttSvg.value
                }, null, 8, _hoisted_19)) : (__mf_132(), __mf_81(__mf_55(_sfc_main$7), {
                  key: 2,
                  headline: "No Gantt yet",
                  body: "Click 'Rebuild artefacts' to generate the Mermaid Gantt diagram."
                }))
              ])) : __mf_82("", true),
              __mf_84("section", null, [
                __mf_84("h2", _hoisted_20, [
                  _cache[18] || (_cache[18] = __mf_90(" Conflicts ", -1)),
                  planner.value ? (__mf_132(), __mf_83("span", _hoisted_21, "(" + __mf_61(planner.value.conflicts.length) + ")", 1)) : __mf_82("", true)
                ]),
                !planner.value || planner.value.conflicts.length === 0 ? (__mf_132(), __mf_81(__mf_55(_sfc_main$7), {
                  key: 0,
                  headline: "No conflicts",
                  body: "No two events overlap in the current window."
                })) : (__mf_132(), __mf_83("div", _hoisted_22, [
                  __mf_84("table", _hoisted_23, [
                    _cache[19] || (_cache[19] = __mf_84("thead", { class: "bg-base-200" }, [
                      __mf_84("tr", null, [
                        __mf_84("th", { class: "text-left px-3 py-2" }, "Event A"),
                        __mf_84("th", { class: "text-left px-3 py-2" }, "Event B"),
                        __mf_84("th", { class: "text-left px-3 py-2" }, "Overlap")
                      ])
                    ], -1)),
                    __mf_84("tbody", null, [
                      (__mf_132(true), __mf_83(__mf_69, null, __mf_138(planner.value.conflicts, (c, idx) => {
                        return __mf_132(), __mf_83("tr", {
                          key: idx,
                          class: "border-t border-base-300"
                        }, [
                          __mf_84("td", _hoisted_24, [
                            __mf_84("div", _hoisted_25, __mf_61(c.titleA), 1),
                            __mf_84("div", _hoisted_26, __mf_61(c.laneA), 1)
                          ]),
                          __mf_84("td", _hoisted_27, [
                            __mf_84("div", _hoisted_28, __mf_61(c.titleB), 1),
                            __mf_84("div", _hoisted_29, __mf_61(c.laneB), 1)
                          ]),
                          __mf_84("td", _hoisted_30, __mf_61(rangeLabel(c)), 1)
                        ]);
                      }), 128))
                    ])
                  ])
                ]))
              ])
            ])) : (__mf_132(), __mf_83("div", _hoisted_31, [
              __mf_84("div", _hoisted_32, [
                __mf_84("h2", _hoisted_33, __mf_61(planner.value?.lanes.find((l) => l.name === activeTab.value)?.title ?? activeTab.value), 1),
                __mf_91(__mf_55(_sfc_main$9), {
                  size: "sm",
                  variant: "primary",
                  onClick: _cache[1] || (_cache[1] = ($event) => openCreateModal(activeTab.value))
                }, {
                  default: __mf_166(() => [..._cache[20] || (_cache[20] = [
                    __mf_90(" + Add event ", -1)
                  ])]),
                  _: 1
                })
              ]),
              __mf_84("div", _hoisted_34, [
                eventsForTab.value.length === 0 ? (__mf_132(), __mf_81(__mf_55(_sfc_main$7), {
                  key: 0,
                  headline: "No events in this lane",
                  body: "Add the first event to get started."
                })) : (__mf_132(), __mf_83("div", _hoisted_35, [
                  (__mf_132(true), __mf_83(__mf_69, null, __mf_138(eventsForTab.value, (ev) => {
                    return __mf_132(), __mf_83("div", {
                      key: ev.id,
                      class: __mf_58(["bg-base-100 border border-base-300 rounded p-3 cursor-pointer hover:border-primary transition-colors", selectedEventId.value === ev.id ? "border-primary ring-1 ring-primary/30" : ""]),
                      onClick: ($event) => selectedEventId.value = ev.id
                    }, [
                      __mf_84("div", _hoisted_37, [
                        __mf_84("div", _hoisted_38, [
                          __mf_84("div", _hoisted_39, __mf_61(ev.title), 1),
                          __mf_84("div", _hoisted_40, [
                            __mf_90(__mf_61(formatDateRange(ev)) + " ", 1),
                            ev.allDay ? (__mf_132(), __mf_83("span", _hoisted_41, "· all-day")) : __mf_82("", true),
                            ev.recurrence ? (__mf_132(), __mf_83("span", _hoisted_42, "· recurring")) : __mf_82("", true)
                          ])
                        ]),
                        __mf_84("div", _hoisted_43, [
                          ev.googleUrl ? (__mf_132(), __mf_83("a", {
                            key: 0,
                            href: ev.googleUrl,
                            target: "_blank",
                            rel: "noopener",
                            class: "text-xs bg-base-200 hover:bg-base-300 rounded px-2 py-1",
                            title: "Add to Google Calendar",
                            onClick: _cache[2] || (_cache[2] = __mf_24(() => {
                            }, ["stop"]))
                          }, "Google", 8, _hoisted_44)) : __mf_82("", true),
                          ev.outlookUrl ? (__mf_132(), __mf_83("a", {
                            key: 1,
                            href: ev.outlookUrl,
                            target: "_blank",
                            rel: "noopener",
                            class: "text-xs bg-base-200 hover:bg-base-300 rounded px-2 py-1",
                            title: "Add to Outlook",
                            onClick: _cache[3] || (_cache[3] = __mf_24(() => {
                            }, ["stop"]))
                          }, "Outlook", 8, _hoisted_45)) : __mf_82("", true)
                        ])
                      ]),
                      ev.location ? (__mf_132(), __mf_83("div", _hoisted_46, " 📍 " + __mf_61(ev.location), 1)) : __mf_82("", true),
                      ev.tags.length > 0 ? (__mf_132(), __mf_83("div", _hoisted_47, [
                        (__mf_132(true), __mf_83(__mf_69, null, __mf_138(ev.tags, (tag) => {
                          return __mf_132(), __mf_83("span", {
                            key: tag,
                            class: "text-xs bg-info/15 text-info rounded px-1.5 py-0.5"
                          }, __mf_61(tag), 1);
                        }), 128))
                      ])) : __mf_82("", true)
                    ], 10, _hoisted_36);
                  }), 128))
                ]))
              ])
            ]))
          ]),
          selectedEvent.value && planner.value ? (__mf_132(), __mf_81(_sfc_main$1, {
            key: 0,
            event: selectedEvent.value,
            lanes: planner.value.lanes,
            class: "w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto",
            onClose: _cache[4] || (_cache[4] = ($event) => selectedEventId.value = null),
            onUpdate: _cache[5] || (_cache[5] = (patch) => onEventUpdate(selectedEvent.value.id, patch)),
            onDelete: _cache[6] || (_cache[6] = ($event) => onEventDelete(selectedEvent.value.id))
          }, null, 8, ["event", "lanes"])) : __mf_82("", true)
        ])),
        __mf_91(__mf_55(_sfc_main$5), {
          modelValue: showCreateModal.value,
          "onUpdate:modelValue": _cache[12] || (_cache[12] = ($event) => showCreateModal.value = $event),
          title: "New event"
        }, {
          default: __mf_166(() => [
            __mf_84("div", _hoisted_48, [
              __mf_91(__mf_55(_sfc_main$6), {
                modelValue: newEventForm.value.title,
                "onUpdate:modelValue": _cache[7] || (_cache[7] = ($event) => newEventForm.value.title = $event),
                placeholder: "Event title"
              }, null, 8, ["modelValue"]),
              __mf_91(__mf_55(_sfc_main$6), {
                modelValue: newEventForm.value.start,
                "onUpdate:modelValue": _cache[8] || (_cache[8] = ($event) => newEventForm.value.start = $event),
                placeholder: "Start (YYYY-MM-DD or YYYY-MM-DDTHH:mm)"
              }, null, 8, ["modelValue"]),
              __mf_91(__mf_55(_sfc_main$6), {
                "model-value": newEventForm.value.end ?? "",
                placeholder: "End (optional)",
                "onUpdate:modelValue": _cache[9] || (_cache[9] = (v) => newEventForm.value.end = v)
              }, null, 8, ["model-value"]),
              __mf_91(__mf_55(_sfc_main$4), {
                "model-value": newEventForm.value.lane ?? "",
                options: planner.value?.lanes.map((l) => ({ value: l.name, label: l.title ?? l.name })) ?? [],
                "onUpdate:modelValue": _cache[10] || (_cache[10] = (v) => newEventForm.value.lane = v ?? "")
              }, null, 8, ["model-value", "options"]),
              __mf_84("div", _hoisted_49, [
                __mf_91(__mf_55(_sfc_main$9), {
                  variant: "ghost",
                  onClick: _cache[11] || (_cache[11] = ($event) => showCreateModal.value = false)
                }, {
                  default: __mf_166(() => [..._cache[21] || (_cache[21] = [
                    __mf_90("Cancel", -1)
                  ])]),
                  _: 1
                }),
                __mf_91(__mf_55(_sfc_main$9), {
                  variant: "primary",
                  disabled: !newEventForm.value.title.trim() || !newEventForm.value.start.trim(),
                  onClick: submitCreate
                }, {
                  default: __mf_166(() => [..._cache[22] || (_cache[22] = [
                    __mf_90("Create", -1)
                  ])]),
                  _: 1
                }, 8, ["disabled"])
              ])
            ])
          ]),
          _: 1
        }, 8, ["modelValue"])
      ]);
    };
  }
});

export { _sfc_main as default };
