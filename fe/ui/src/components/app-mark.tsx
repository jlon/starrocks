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
        'relative inline-flex items-center justify-center overflow-hidden rounded-xl bg-gradient-to-br from-cyan-400 via-blue-500 to-indigo-600 text-white shadow-sm',
        className ?? 'size-9'
      )}
    >
      <Database className={cn('size-[45%]', iconClassName)} />
      <span
        className='absolute top-[12%] right-[12%] size-[10%] rounded-full bg-white/90'
        aria-hidden='true'
      />
    </span>
  )
}
