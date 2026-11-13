import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Copy, Search } from 'lucide-react'
import { toast } from 'sonner'
import { PageHeader } from '@/components/page-header'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { apiFetch } from '@/lib/api'

type QueryProfile = {
  queryId: string
  profile: string
}

export function QueryProfilePage({ initialQueryId }: { initialQueryId?: string }) {
  const [input, setInput] = useState(initialQueryId ?? '')
  const [queryId, setQueryId] = useState(initialQueryId ?? '')

  useEffect(() => {
    if (initialQueryId) {
      setInput(initialQueryId)
      setQueryId(initialQueryId)
    }
  }, [initialQueryId])

  const { data, isLoading, error } = useQuery({
    queryKey: ['query-profile', queryId],
    queryFn: () =>
      apiFetch<QueryProfile>(
        `/api/web/v1/queries/${encodeURIComponent(queryId)}/profile`
      ),
    enabled: queryId.length > 0,
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Query Profile'
        description='Inspect the runtime profile of a running or finished query.'
      />

      <form
        className='mb-4 flex max-w-xl gap-2'
        onSubmit={(event) => {
          event.preventDefault()
          setQueryId(input.trim())
        }}
      >
        <Input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder='Query ID'
          className='font-mono'
        />
        <Button type='submit' disabled={!input.trim()}>
          <Search /> Load
        </Button>
      </form>

      {error && <p className='text-destructive text-sm'>{error.message}</p>}
      {isLoading && <Skeleton className='h-64 w-full rounded-xl' />}

      {data && (
        <div className='rounded-xl border'>
          <div className='flex items-center justify-between border-b px-4 py-2'>
            <span className='text-muted-foreground font-mono text-xs'>
              {data.queryId}
            </span>
            <Button
              variant='ghost'
              size='sm'
              onClick={() => {
                navigator.clipboard.writeText(data.profile)
                toast.success('Profile copied')
              }}
            >
              <Copy /> Copy
            </Button>
          </div>
          <pre className='max-h-[70vh] overflow-auto p-4 text-xs leading-relaxed'>
            {data.profile}
          </pre>
        </div>
      )}

      {!queryId && (
        <p className='text-muted-foreground text-sm'>
          Enter a query ID or open a query from the Queries page.
        </p>
      )}
    </div>
  )
}
