<script setup lang="ts">
/**
 * Konami-code easter egg for the login/landing page only.
 *
 * ↑ ↑ ↓ ↓ ← → ← → B A  reveals a full-screen "DON'T PANIC" overlay
 * with a mock Hitchhiker's-Guide entry about Vance that types itself
 * out, ending on a big friendly 42. Fully self-contained: its own
 * window keydown listener, its own overlay, its own scoped CSS — it
 * touches nothing in the login flow and renders no DOM until armed.
 *
 * A wink for anyone who noticed the engines are all named after
 * H2G2 characters (arthur, ford, marvin, trillian, eddie, …).
 */
import { onBeforeUnmount, onMounted, ref } from 'vue';

const KONAMI = [
  'ArrowUp',
  'ArrowUp',
  'ArrowDown',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'ArrowLeft',
  'ArrowRight',
  'b',
  'a',
] as const;

/**
 * The Guide entry, in the driest Adams register we could manage.
 * Rendered one character at a time.
 */
const GUIDE_ENTRY =
  'The Guide has this to say on the subject of Vance:\n\n' +
  '"Vance is a mostly harmless workbench. It has been described as a ' +
  'place where documents go to become agents, and where agents ' +
  'occasionally return the favour.\n\n' +
  'It is run by a small, tireless assistant who is convinced that ' +
  'everything will be fine, which is more than can be said for the ' +
  'assistant fitted to most ships.\n\n' +
  'The Answer to Life, the Universe, and Everything remains 42. ' +
  'Vance will at least help you format it."';

/** Buffer of recent keys, trimmed to the sequence length. */
const buffer: string[] = [];

const armed = ref(false);
const revealed = ref(0);
const showAnswer = ref(false);

let typer: ReturnType<typeof setInterval> | null = null;
let answerTimer: ReturnType<typeof setTimeout> | null = null;

function trigger(): void {
  if (armed.value) return;
  armed.value = true;
  revealed.value = 0;
  showAnswer.value = false;
  // Typewriter — steady reveal, a touch faster on whitespace so the
  // paragraph breaks don't stall the rhythm.
  typer = setInterval(() => {
    revealed.value += 1;
    if (revealed.value >= GUIDE_ENTRY.length) {
      stopTyper();
      answerTimer = setTimeout(() => (showAnswer.value = true), 400);
    }
  }, 22);
}

function stopTyper(): void {
  if (typer !== null) {
    clearInterval(typer);
    typer = null;
  }
}

function dismiss(): void {
  stopTyper();
  if (answerTimer !== null) {
    clearTimeout(answerTimer);
    answerTimer = null;
  }
  armed.value = false;
  buffer.length = 0;
}

function onKeydown(ev: KeyboardEvent): void {
  // While the overlay is up, any key sends it away (except while
  // still typing — then the first key fast-forwards to the end).
  if (armed.value) {
    ev.preventDefault();
    if (revealed.value < GUIDE_ENTRY.length) {
      stopTyper();
      revealed.value = GUIDE_ENTRY.length;
      answerTimer = setTimeout(() => (showAnswer.value = true), 300);
    } else {
      dismiss();
    }
    return;
  }

  // Match the Konami key regardless of shift/caps for the letters.
  const key = ev.key.length === 1 ? ev.key.toLowerCase() : ev.key;
  buffer.push(key);
  if (buffer.length > KONAMI.length) buffer.shift();
  if (buffer.length === KONAMI.length && KONAMI.every((k, i) => buffer[i] === k)) {
    buffer.length = 0;
    trigger();
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown));
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  dismiss();
});
</script>

<template>
  <Transition name="panic-fade">
    <div v-if="armed" class="panic-overlay" role="dialog" aria-label="Don't Panic" @click="dismiss">
      <div class="panic-inner" @click.stop>
        <div class="panic-title">DON'T PANIC</div>
        <pre class="panic-entry">{{ GUIDE_ENTRY.slice(0, revealed) }}<span
            v-if="revealed < GUIDE_ENTRY.length"
            class="panic-cursor"
          >▊</span></pre>
        <Transition name="panic-answer">
          <div v-if="showAnswer" class="panic-answer">42</div>
        </Transition>
        <div class="panic-hint">press any key</div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
/* Deliberately DaisyUI-free and theme-independent: this is a
 * decorative full-bleed overlay, not part of the component system,
 * so it carries its own palette (Guide green on near-black). */
.panic-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  background: radial-gradient(ellipse at center, #041007 0%, #000 75%);
  cursor: pointer;
}

.panic-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1.75rem;
  max-width: 42rem;
  width: 100%;
  cursor: default;
}

.panic-title {
  font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
  font-weight: 900;
  font-size: clamp(2.5rem, 9vw, 5.5rem);
  letter-spacing: 0.06em;
  color: #00ff5f;
  text-align: center;
  text-shadow:
    0 0 8px rgba(0, 255, 95, 0.7),
    0 0 28px rgba(0, 255, 95, 0.45),
    0 0 60px rgba(0, 255, 95, 0.25);
  animation: panic-pulse 1.6s ease-in-out infinite;
}

.panic-entry {
  font-family: 'SFMono-Regular', ui-monospace, 'Menlo', monospace;
  font-size: clamp(0.8rem, 2.2vw, 1rem);
  line-height: 1.6;
  color: #baf7cf;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  min-height: 12rem;
  text-shadow: 0 0 6px rgba(0, 255, 95, 0.2);
}

.panic-cursor {
  animation: panic-blink 1s step-end infinite;
  color: #00ff5f;
}

.panic-answer {
  font-family: system-ui, -apple-system, sans-serif;
  font-weight: 900;
  font-size: clamp(3rem, 12vw, 7rem);
  line-height: 1;
  color: #00ff5f;
  text-shadow:
    0 0 12px rgba(0, 255, 95, 0.7),
    0 0 40px rgba(0, 255, 95, 0.35);
}

.panic-hint {
  font-family: ui-monospace, monospace;
  font-size: 0.7rem;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: rgba(186, 247, 207, 0.4);
}

@keyframes panic-pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.82;
    transform: scale(1.02);
  }
}

@keyframes panic-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

/* Overlay enter/leave */
.panic-fade-enter-active,
.panic-fade-leave-active {
  transition: opacity 0.35s ease;
}
.panic-fade-enter-from,
.panic-fade-leave-to {
  opacity: 0;
}

/* The 42 swells in */
.panic-answer-enter-active {
  transition:
    opacity 0.6s ease,
    transform 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}
.panic-answer-enter-from {
  opacity: 0;
  transform: scale(0.4);
}

@media (prefers-reduced-motion: reduce) {
  .panic-title {
    animation: none;
  }
  .panic-answer-enter-active {
    transition: opacity 0.3s ease;
  }
  .panic-answer-enter-from {
    transform: none;
  }
}
</style>
