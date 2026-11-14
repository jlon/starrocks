import { type LucideIcon, Inbox } from 'lucide-react'
import { cn } from '@/lib/utils'

export function EmptyState({
  icon: Icon = Inbox,
  title,
  description,
  className,
}: {
  icon?: LucideIcon
  title: string
  description?: string
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-2 py-10 text-center',
        className
      )}
    >
      <span className='inline-flex size-10 items-center justify-center rounded-xl border bg-muted text-muted-foreground [&>svg]:size-5'>
        <Icon />
      </span>
      <p className='text-sm font-medium'>{title}</p>
      {description && (
        <p className='max-w-sm text-xs text-muted-foreground'>{description}</p>
      )}
    </div>
  )
}
