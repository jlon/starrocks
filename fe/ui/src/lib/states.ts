export type Tone =
  | 'green'
  | 'amber'
  | 'red'
  | 'sky'
  | 'violet'
  | 'teal'
  | 'orange'
  | 'blue'
  | 'neutral'

export const TONE_CHIP: Record<Tone, string> = {
  green:
    'border-emerald-500/25 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  amber: 'border-amber-500/25 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  red: 'border-rose-500/25 bg-rose-500/10 text-rose-700 dark:text-rose-300',
  sky: 'border-sky-500/25 bg-sky-500/10 text-sky-700 dark:text-sky-300',
  violet:
    'border-violet-500/25 bg-violet-500/10 text-violet-700 dark:text-violet-300',
  teal: 'border-teal-500/25 bg-teal-500/10 text-teal-700 dark:text-teal-300',
  orange:
    'border-orange-500/25 bg-orange-500/10 text-orange-700 dark:text-orange-300',
  blue: 'border-blue-500/25 bg-blue-500/10 text-blue-700 dark:text-blue-300',
  neutral: 'border-border bg-muted text-muted-foreground',
}

export const TONE_DOT: Record<Tone, string> = {
  green: 'bg-emerald-500',
  amber: 'bg-amber-500',
  red: 'bg-rose-500',
  sky: 'bg-sky-500',
  violet: 'bg-violet-500',
  teal: 'bg-teal-500',
  orange: 'bg-orange-500',
  blue: 'bg-blue-500',
  neutral: 'bg-muted-foreground/50',
}

const STATE_TONES: [Tone, RegExp][] = [
  ['green', /^(finished|ok|success|alive|connected)$/],
  ['red', /^(error|failed|dead|aborted|cancelled|exception|fatal)$/],
  ['amber', /^(pending|waiting|queued|sleep|warning|warn)$/],
  ['sky', /^(running|executing|in progress|ready|using)$/],
  ['violet', /^(leader|follower|observer|replayer)$/],
]

export function stateTone(state: string | undefined | null): Tone {
  const normalized = (state ?? '').trim().toLowerCase()
  if (!normalized || normalized === '-' || normalized === 'unknown') {
    return 'neutral'
  }
  for (const [tone, pattern] of STATE_TONES) {
    if (pattern.test(normalized)) return tone
  }
  return 'neutral'
}

const QUERY_TYPE_TONES: Record<string, Tone> = {
  select: 'blue',
  analyze: 'teal',
  insert: 'amber',
  load: 'orange',
  update: 'amber',
  delete: 'red',
  ddl: 'violet',
  schema_change: 'violet',
}

export function queryTypeTone(type: string | undefined | null): Tone {
  const normalized = (type ?? '').trim().toLowerCase()
  return QUERY_TYPE_TONES[normalized] ?? 'neutral'
}

export function roleTone(role: string | undefined | null): Tone {
  const normalized = (role ?? '').trim().toLowerCase()
  if (normalized === 'leader') return 'green'
  if (normalized === 'observer') return 'amber'
  if (normalized === 'follower') return 'violet'
  return 'neutral'
}
