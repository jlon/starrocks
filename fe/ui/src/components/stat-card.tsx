import { type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { TONE_CHIP, type Tone } from '@/lib/states'
import { Card, CardContent } from '@/components/ui/card'

export function StatCard({
  title,
  value,
  detail,
  icon,
  tone = 'neutral',
  className,
}: {
  title: ReactNode
  value: ReactNode
  detail?: ReactNode
  icon?: ReactNode
  tone?: Tone
  className?: string
}) {
  return (
    <Card className={cn('wc-enter transition-shadow hover:shadow-md', className)}>
      <CardContent className='flex items-start gap-3 p-5'>
        {icon && (
          <span
            className={cn(
              'inline-flex size-9 shrink-0 items-center justify-center rounded-lg border [&>svg]:size-4',
              TONE_CHIP[tone]
            )}
          >
            {icon}
          </span>
        )}
        <div className='min-w-0'>
          <div className='text-xs font-medium tracking-wide text-muted-foreground uppercase'>
            {title}
          </div>
          <div className='mt-1 text-2xl font-semibold tracking-tight tabular-nums'>
            {value}
          </div>
          {detail && (
            <div className='mt-1 text-xs text-muted-foreground'>{detail}</div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
