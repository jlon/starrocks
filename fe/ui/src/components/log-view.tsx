import { Fragment, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

const TIMESTAMP =
  /(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?)/
const LEVEL =
  /\b(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL|SEVERE|CRITICAL)\b/

const LEVEL_CLASS: Record<string, string> = {
  TRACE: 'text-zinc-500',
  DEBUG: 'text-sky-300',
  INFO: 'text-emerald-400',
  WARN: 'text-amber-400',
  WARNING: 'text-amber-400',
  ERROR: 'text-red-400',
  FATAL: 'text-red-500',
  SEVERE: 'text-red-500',
  CRITICAL: 'text-red-500',
}

function colorize(segment: string, keyPrefix: string): ReactNode[] {
  return segment.split(TIMESTAMP).map((part, index) => {
    if (!part) return null
    if (TIMESTAMP.test(part)) {
      return (
        <span key={`${keyPrefix}-${index}`} className='text-zinc-500'>
          {part}
        </span>
      )
    }
    return <Fragment key={`${keyPrefix}-${index}`}>{part}</Fragment>
  })
}

function renderLine(line: string, index: number): ReactNode {
  const parts = line.split(LEVEL)
  return (
    <Fragment key={index}>
      {parts.map((part, partIndex) => {
        if (partIndex % 2 === 1) {
          return (
            <span
              key={partIndex}
              className={cn('font-medium', LEVEL_CLASS[part] ?? 'text-zinc-300')}
            >
              {part}
            </span>
          )
        }
        return <Fragment key={partIndex}>{colorize(part, `${index}-${partIndex}`)}</Fragment>
      })}
      {'\n'}
    </Fragment>
  )
}

export function LogView({
  text,
  className,
}: {
  text: string
  className?: string
}) {
  return (
    <pre
      className={cn(
        'max-h-[65vh] overflow-auto rounded-xl bg-zinc-950 p-4 font-mono text-xs leading-relaxed text-zinc-300 whitespace-pre-wrap',
        className
      )}
    >
      {text.split('\n').map(renderLine)}
    </pre>
  )
}
