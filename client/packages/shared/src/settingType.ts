import { SettingType } from '@vance/generated';

/**
 * Whether values of this setting type are encrypted at rest — and therefore
 * masked by the server on read (`[set]` instead of the value).
 *
 * Mirrors `SettingType.encrypted()` on the Java side. UI code must use this
 * rather than comparing against `SettingType.PASSWORD`: a bare comparison
 * treats `HIDDEN` as a plain value, so an editor would prefill the mask and
 * write it back as the new secret.
 *
 * The two encrypted types differ only in whether an authored `{{secret:…}}`
 * reference may resolve them — `PASSWORD` is server-internal, `HIDDEN` is
 * resolvable by tools and scripts. Neither is ever readable through the
 * settings API.
 */
export function isEncryptedSettingType(type: SettingType | string | null | undefined): boolean {
  return type === SettingType.PASSWORD || type === SettingType.HIDDEN;
}
