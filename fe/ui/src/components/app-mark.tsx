import { Database } from 'lucide-react'
import { cn } from '@/lib/utils'

export function AppMark({
  className,
  iconClassName,
}: {
  className?: string
  iconClassName?: string
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 text-white shadow-sm',
        className ?? 'size-9'
      )}
    >
      <Database className={cn('size-[45%]', iconClassName)} />
    </span>
  )
}
