import { type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { TONE_CHIP, TONE_DOT, type Tone } from '@/lib/states'
import { Badge } from '@/components/ui/badge'

export function StateChip({
  tone,
  children,
  className,
}: {
  tone: Tone
  children: ReactNode
  className?: string
}) {
  return (
    <Badge variant='outline' className={cn(TONE_CHIP[tone], className)}>
      {children}
    </Badge>
  )
}

export function StateBadge({
  state,
  tone,
  label,
  className,
}: {
  state?: string | null
  tone?: Tone
  label?: string
  className?: string
}) {
  const text = label ?? (state?.trim() || '-')
  const resolved = tone ?? 'neutral'
  return (
    <StateChip tone={resolved} className={className}>
      <span
        className={cn('inline-block size-1.5 rounded-full', TONE_DOT[resolved])}
      />
      {text}
    </StateChip>
  )
}
