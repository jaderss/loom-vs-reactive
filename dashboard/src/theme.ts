import { useSyncExternalStore } from 'react'
import { SERIES, type Series } from './data'

const darkQuery = window.matchMedia('(prefers-color-scheme: dark)')

function subscribe(onChange: () => void) {
  darkQuery.addEventListener('change', onChange)
  return () => darkQuery.removeEventListener('change', onChange)
}

const read = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim()

export type Theme = {
  series: Record<Series, string>
  grid: string
  axis: string
  muted: string
  surface: string
}

let cached: { dark: boolean; theme: Theme } | undefined

function snapshot(): Theme {
  if (cached?.dark !== darkQuery.matches) {
    cached = {
      dark: darkQuery.matches,
      theme: {
        series: Object.fromEntries(SERIES.map((s) => [s, read(`--series-${s}`)])) as Record<Series, string>,
        grid: read('--grid'),
        axis: read('--axis'),
        muted: read('--text-muted'),
        surface: read('--surface-1'),
      },
    }
  }
  return cached.theme
}

export const useTheme = () => useSyncExternalStore(subscribe, snapshot)
