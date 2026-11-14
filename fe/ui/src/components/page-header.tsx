import type { ReactNode } from 'react'

type PageHeaderProps = {
  title: string
  description?: string
  actions?: ReactNode
}

export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <div className='wc-enter mb-6 flex flex-wrap items-start justify-between gap-3'>
      <div>
        <h1 className='text-2xl font-semibold tracking-tight'>{title}</h1>
        <div className='mt-2 h-px w-10 rounded-full bg-gradient-to-r from-sky-500 via-blue-600 to-transparent' />
        {description && (
          <p className='mt-1.5 text-sm text-muted-foreground'>{description}</p>
        )}
      </div>
      {actions && <div className='flex items-center gap-2'>{actions}</div>}
    </div>
  )
}
