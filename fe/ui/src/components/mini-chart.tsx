import { cn } from '@/lib/utils'

const TONE_HEX: Record<string, string> = {
  blue: '#2563eb',
  sky: '#0284c7',
  teal: '#0d9488',
  green: '#059669',
  amber: '#d97706',
  orange: '#ea580c',
  red: '#e11d48',
  violet: '#7c3aed',
  neutral: '#71717a',
}

export function toneColor(tone: string): string {
  return TONE_HEX[tone] ?? TONE_HEX.neutral
}

type GaugeProps = {
  /** 0-100 */
  value: number
  label: string
  tone?: string
  size?: number
  className?: string
}

/** Radial gauge — compact, for cluster resource cards. */
export function Gauge({ value, label, tone = 'blue', size = 72, className }: GaugeProps) {
  const clamped = Math.min(100, Math.max(0, value))
  const radius = (size - 8) / 2
  const circumference = 2 * Math.PI * radius
  const offset = circumference * (1 - clamped / 100)
  const color = toneColor(tone)

  return (
    <div className={cn('flex flex-col items-center gap-1', className)}>
      <div className='relative' style={{ width: size, height: size }}>
        <svg width={size} height={size} className='-rotate-90'>
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill='none'
            strokeWidth={6}
            className='stroke-muted'
          />
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill='none'
            strokeWidth={6}
            strokeLinecap='round'
            stroke={color}
            strokeDasharray={circumference}
            strokeDashoffset={offset}
          />
        </svg>
        <div className='absolute inset-0 flex items-center justify-center text-sm font-semibold tabular-nums'>
          {clamped.toFixed(clamped < 10 ? 1 : 0)}%
        </div>
      </div>
      <span className='text-xs text-muted-foreground'>{label}</span>
    </div>
  )
}

type BarProps = {
  value: number
  max: number
  tone?: string
  label?: string
  right?: string
  className?: string
}

/** Thin horizontal bar with optional label and right-side value. */
export function MeterBar({ value, max, tone = 'blue', label, right, className }: BarProps) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0
  return (
    <div className={cn('space-y-1', className)}>
      {(label || right) && (
        <div className='flex items-baseline justify-between text-xs'>
          <span className='text-muted-foreground'>{label}</span>
          <span className='font-medium tabular-nums'>{right}</span>
        </div>
      )}
      <div className='h-1.5 overflow-hidden rounded-full bg-muted'>
        <div
          className='h-full rounded-full transition-all'
          style={{ width: `${pct}%`, backgroundColor: toneColor(tone) }}
        />
      </div>
    </div>
  )
}

type Slice = { label: string; value: number; tone: string }

type DonutProps = {
  slices: Slice[]
  size?: number
  thickness?: number
  center?: React.ReactNode
  className?: string
}

/** Donut chart for categorical splits (query types, session commands). */
export function Donut({ slices, size = 120, thickness = 14, center, className }: DonutProps) {
  const total = slices.reduce((sum, slice) => sum + slice.value, 0)
  const radius = (size - thickness) / 2
  const circumference = 2 * Math.PI * radius
  let accumulated = 0

  return (
    <div className={cn('flex items-center gap-4', className)}>
      <div className='relative shrink-0' style={{ width: size, height: size }}>
        <svg width={size} height={size} className='-rotate-90'>
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill='none'
            strokeWidth={thickness}
            className='stroke-muted'
          />
          {total > 0 &&
            slices.map((slice) => {
              const fraction = slice.value / total
              const dash = fraction * circumference
              const gap = circumference - dash
              const rotation = (accumulated / total) * 360
              accumulated += slice.value
              return (
                <circle
                  key={slice.label}
                  cx={size / 2}
                  cy={size / 2}
                  r={radius}
                  fill='none'
                  strokeWidth={thickness}
                  stroke={toneColor(slice.tone)}
                  strokeDasharray={`${dash} ${gap}`}
                  strokeDashoffset={-rotation * (circumference / 360)}
                />
              )
            })}
        </svg>
        {center && (
          <div className='absolute inset-0 flex items-center justify-center'>
            {center}
          </div>
        )}
      </div>
      <div className='min-w-0 space-y-1'>
        {slices.map((slice) => (
          <div key={slice.label} className='flex items-center gap-2 text-xs'>
            <span
              className='size-2 shrink-0 rounded-full'
              style={{ backgroundColor: toneColor(slice.tone) }}
            />
            <span className='truncate text-muted-foreground'>{slice.label}</span>
            <span className='ml-auto font-medium tabular-nums'>{slice.value}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
