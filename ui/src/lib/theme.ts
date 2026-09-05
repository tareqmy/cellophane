export type Theme = 'auto' | 'light' | 'dark'

const KEY = 'cellophane.theme'

/** Theme preference: ?theme= in the URL wins (handy for screenshots), then localStorage, then the OS setting. */
export function loadTheme(): Theme {
  const fromUrl = new URLSearchParams(location.search).get('theme')
  if (fromUrl === 'light' || fromUrl === 'dark') return fromUrl
  try {
    const stored = localStorage.getItem(KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    /* storage unavailable */
  }
  return 'auto'
}

export function applyTheme(theme: Theme) {
  if (theme === 'auto') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = theme
  try {
    if (theme === 'auto') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, theme)
  } catch {
    /* storage unavailable */
  }
}

export function nextTheme(theme: Theme): Theme {
  return theme === 'auto' ? 'dark' : theme === 'dark' ? 'light' : 'auto'
}
