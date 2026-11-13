import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'

type AutoRefreshProps = {
  enabled: boolean
  onChange: (enabled: boolean) => void
  onRefresh: () => void
  pending?: boolean
}

export function AutoRefresh({
  enabled,
  onChange,
  onRefresh,
  pending,
}: AutoRefreshProps) {
  return (
    <div className='flex items-center gap-2'>
      <Button
        type='button'
        variant='outline'
        size='sm'
        onClick={onRefresh}
        disabled={pending}
      >
        <RefreshCw className={pending ? 'animate-spin' : undefined} />
        Refresh
      </Button>
      <Button
        type='button'
        variant={enabled ? 'secondary' : 'outline'}
        size='sm'
        aria-pressed={enabled}
        onClick={() => onChange(!enabled)}
      >
        {enabled && (
          <span
            className='wc-pulse-dot size-1.5 rounded-full bg-emerald-500'
            aria-hidden='true'
          />
        )}
        Auto 10s
      </Button>
    </div>
  )
}
