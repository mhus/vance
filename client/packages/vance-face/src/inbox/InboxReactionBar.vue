<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { VButton } from '@vance/components';
import { getUsername } from '@vance/shared';
import type { MaximegalonReactionDto } from '@vance/generated';

/**
 * The reactions on one node — the thread's own question, one message, or a
 * thread as it appears in the list.
 *
 * <p>Its own component because the list needs exactly what the thread panel
 * needs: the chips that are there, and one button to add another. Reacting from
 * the list is the point of the quiet channel — agreeing with a question should
 * not cost opening it.
 */

const props = defineProps<{
  /** The node's reactions, as they came from the server. */
  reactions?: MaximegalonReactionDto[] | null;
  busy?: boolean;
}>();

const emit = defineEmits<{
  (e: 'react', key: string, on: boolean): void;
}>();

const { t } = useI18n();
const me = computed<string>(() => getUsername() ?? '');

/**
 * The reaction palette, as explicit shortcode/character pairs.
 *
 * <p>Not {@code VEmojiPicker}: that one emits the unicode character and offers
 * a topic set built for document icons. The wire format wants shortcodes on
 * purpose — 👍 and 👍🏽 are different codepoints and would file the same
 * reaction twice. A fixed palette sidesteps the mapping entirely, and these six
 * are the ones that do work in a clarification: agree, on it, done, unclear,
 * thanks, celebrate.
 */
const PALETTE: ReadonlyArray<{ key: string; char: string }> = [
  { key: 'thumbsup', char: '👍' },
  { key: 'eyes', char: '👀' },
  { key: 'white_check_mark', char: '✅' },
  { key: 'question', char: '❓' },
  { key: 'pray', char: '🙏' },
  { key: 'tada', char: '🎉' },
];

/**
 * Only the reactions somebody actually gave, as chips — the way Slack does it.
 *
 * <p>Rendering the whole palette everywhere was the first attempt and it is
 * noise: six buttons per message plus six on the thread is thirty controls on a
 * short discussion, and the count of interest (how many agreed) drowns among
 * the ones nobody pressed. Existing reactions carry information; empty ones
 * carry only the offer, and one button is enough to make the offer.
 */
const chips = computed<Array<{ key: string; char: string; count: number; mine: boolean }>>(() =>
  (props.reactions ?? [])
    .filter((r) => (r.userIds?.length ?? 0) > 0)
    .map((r) => ({
      key: r.key,
      // Unknown keys still render: the palette may grow, and a reaction from a
      // newer client must not vanish from an older one. The shortcode is a
      // readable fallback.
      char: PALETTE.find((p) => p.key === r.key)?.char ?? `:${r.key}:`,
      count: r.userIds?.length ?? 0,
      mine: r.userIds?.includes(me.value) ?? false,
    })));

/**
 * Whether the palette is expanded here.
 *
 * <p>Explicit state and an inline expansion, not a dropdown: DaisyUI's dropdown
 * opens on `:focus-within`, so clicking a button inside it takes focus off the
 * trigger and the menu is gone before the click lands. That is fine for
 * `<li><a>` navigation, which is what {@code VDropdown} is built for, and wrong
 * for a grid of buttons — the first attempt looked right and silently did
 * nothing. Expanding in place needs no positioning and no focus timing.
 */
const paletteOpen = ref(false);

function pick(key: string): void {
  const mine = props.reactions?.find((r) => r.key === key)?.userIds?.includes(me.value) ?? false;
  emit('react', key, !mine);
  paletteOpen.value = false;
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-1">
    <VButton
      v-for="c in chips"
      :key="c.key"
      size="sm"
      :variant="c.mine ? 'primary' : 'ghost'"
      :title="c.key"
      :disabled="busy"
      @click="emit('react', c.key, !c.mine)"
    >{{ c.char }}<span class="ml-1 text-xs">{{ c.count }}</span></VButton>
    <VButton
      size="sm"
      variant="ghost"
      :title="t('inboxThread.addReaction')"
      :disabled="busy"
      @click="paletteOpen = !paletteOpen"
    >&#9786;+</VButton>
    <template v-if="paletteOpen">
      <VButton
        v-for="r in PALETTE"
        :key="r.key"
        size="sm"
        variant="ghost"
        :title="r.key"
        :disabled="busy"
        @click="pick(r.key)"
      >{{ r.char }}</VButton>
    </template>
    <slot />
  </div>
</template>
