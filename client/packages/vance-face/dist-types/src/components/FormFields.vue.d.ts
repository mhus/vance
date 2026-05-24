import type { FormFieldDto } from '@vance/generated';
/**
 * Universal form renderer driven by {@link FormFieldDto} schemas.
 *
 * <p>Used by Prompt-Wizards (chat editor) and Kit-Tool-Templates —
 * everything UI primitive lives in {@code src/components/} so editor
 * code only ever sees this composite.
 *
 * <p>Field types: {@code string}, {@code textarea}, {@code password},
 * {@code integer}, {@code boolean}, {@code select}, {@code multi_select},
 * {@code repeat}. Localized labels resolve against the active
 * {@code useI18n().locale} (or the {@code preferredLang} prop when
 * the host wants to force a different language).
 *
 * <p>Value encoding follows the tool-template convention: booleans /
 * integers / selects are stored as strings inside the modelValue
 * map. Multi-selects are {@code string[]}. Repeat fields are
 * {@code Array<Record<fieldName, string | string[]>>} — nested
 * repeats are intentionally <em>not</em> supported in v1.
 */
export type FormValue = string | string[] | FormValueObject[];
export type FormValueObject = Record<string, string | string[]>;
interface Props {
    fields: FormFieldDto[];
    modelValue: Record<string, FormValue>;
    /** Map of field-path → error code (e.g. "members[2].name" → "required"). */
    errors?: Record<string, string>;
    /** Override the active i18n locale for label resolution. */
    preferredLang?: string;
    /** Path prefix for nested error keys (used by repeat-recursion). */
    pathPrefix?: string;
    disabled?: boolean;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (value: Record<string, FormValue>) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((value: Record<string, FormValue>) => any) | undefined;
}>, {
    disabled: boolean;
    errors: Record<string, string>;
    preferredLang: string;
    pathPrefix: string;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=FormFields.vue.d.ts.map