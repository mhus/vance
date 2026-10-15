/**
 * The shell follows the system appearance (corporate-design.md §3): the
 * same `data-theme` + `dark` class switch the web UI uses, so tokens and
 * DaisyUI themes apply unchanged.
 */
export function followSystemTheme(onChange?: (dark: boolean) => void): void {
  const query = window.matchMedia('(prefers-color-scheme: dark)');
  const apply = (dark: boolean): void => {
    const root = document.documentElement;
    root.dataset.theme = dark ? 'dark' : 'light';
    root.classList.toggle('dark', dark);
    onChange?.(dark);
  };
  apply(query.matches);
  query.addEventListener('change', (event) => apply(event.matches));
}
