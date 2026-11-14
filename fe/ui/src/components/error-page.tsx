import { type ReactNode } from 'react'
import { useNavigate, useRouter } from '@tanstack/react-router'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { AppMark } from '@/components/app-mark'

export function ErrorPage({
  code,
  title,
  description,
  className,
  actions,
}: {
  code: string
  title: string
  description: ReactNode
  className?: string
  actions?: ReactNode
}) {
  const navigate = useNavigate()
  const { history } = useRouter()
  return (
    <div
      className={cn(
        'flex min-h-svh items-center justify-center bg-muted/40 p-4',
        className
      )}
    >
      <Card className='wc-enter w-full max-w-md text-center shadow-lg'>
        <CardContent className='flex flex-col items-center gap-3 p-8'>
          <AppMark className='size-12' iconClassName='size-6' />
          <p className='font-mono text-6xl font-bold tracking-tight text-muted-foreground/60'>
            {code}
          </p>
          <h1 className='text-lg font-semibold'>{title}</h1>
          <p className='text-sm text-muted-foreground'>{description}</p>
          <div className='mt-4 flex gap-3'>
            {actions ?? (
              <>
                <Button variant='outline' onClick={() => history.go(-1)}>
                  Go Back
                </Button>
                <Button onClick={() => navigate({ to: '/' })}>Home</Button>
              </>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
