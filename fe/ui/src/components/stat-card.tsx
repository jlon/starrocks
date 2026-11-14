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
      <CardContent className='flex items-center gap-3 p-4'>
        {icon && (
          <span
            className={cn(
              'inline-flex size-8 shrink-0 items-center justify-center rounded-lg border [&>svg]:size-4',
              TONE_CHIP[tone]
            )}
          >
            {icon}
          </span>
        )}
        <div className='min-w-0'>
          <div className='text-[11px] font-medium tracking-wide text-muted-foreground uppercase'>
            {title}
          </div>
          <div className='mt-0.5 text-xl font-semibold tracking-tight tabular-nums'>
            {value}
          </div>
          {detail && (
            <div className='mt-0.5 text-[11px] text-muted-foreground'>
              {detail}
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
