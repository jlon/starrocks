import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Copy, Download, Search } from 'lucide-react'
import { toast } from 'sonner'
import { apiFetch } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PageHeader } from '@/components/page-header'
import { ProfileTree } from './profile-tree'

type ProfileType = 'profile' | 'sql' | 'analyze'
type QueryProfile = {
  queryId: string
  type: ProfileType
  content: string
}

export function QueryProfilePage({
  initialQueryId,
}: {
  initialQueryId?: string
}) {
  const [input, setInput] = useState(initialQueryId ?? '')
  const [queryId, setQueryId] = useState(initialQueryId ?? '')
  const [type, setType] = useState<ProfileType>('profile')
  const navigate = useNavigate() as unknown as (options: {
    search: { queryId?: string }
    replace?: boolean
  }) => void

  const { data, isLoading, error } = useQuery({
    queryKey: ['query-profile', queryId, type],
    queryFn: () =>
      apiFetch<QueryProfile>(
        `/api/web/v1/queries/${encodeURIComponent(queryId)}/profile?type=${type}`
      ),
    enabled: queryId.length > 0,
  })

  const download = () => {
    if (!data) return
    const blob = new Blob([data.content], { type: 'text/plain;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${data.queryId}.${data.type}.txt`
    link.click()
    URL.revokeObjectURL(url)
  }

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
          const next = input.trim()
          setQueryId(next)
          void navigate({
            search: { queryId: next || undefined },
            replace: true,
          })
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

      {error && (
        <p className='mb-2 text-sm text-destructive'>{error.message}</p>
      )}
      {isLoading && <Skeleton className='h-64 w-full rounded-xl' />}

      {data && (
        <Tabs
          value={type}
          onValueChange={(value) => setType(value as ProfileType)}
        >
          <div className='flex flex-wrap items-center justify-between gap-2'>
            <TabsList>
              <TabsTrigger value='profile'>Profile</TabsTrigger>
              <TabsTrigger value='sql'>Formatted SQL</TabsTrigger>
              <TabsTrigger value='analyze'>Analyze</TabsTrigger>
            </TabsList>
            <div className='flex items-center gap-2'>
              <Button
                variant='ghost'
                size='sm'
                onClick={() => {
                  void navigator.clipboard.writeText(data.content)
                  toast.success('Copied')
                }}
              >
                <Copy /> Copy
              </Button>
              <Button variant='ghost' size='sm' onClick={download}>
                <Download /> Download
              </Button>
            </div>
          </div>

          <p className='mt-2 mb-2 font-mono text-xs text-muted-foreground'>
            {data.queryId}
          </p>

          <TabsContent value='profile' className='rounded-xl border'>
            <ProfileTree
              key={`${data.queryId}-${data.type}`}
              text={data.content}
            />
          </TabsContent>
          <TabsContent value='sql' className='rounded-xl border'>
            <pre className='max-h-[70vh] overflow-auto p-4 text-xs leading-relaxed'>
              {data.content}
            </pre>
          </TabsContent>
          <TabsContent value='analyze' className='rounded-xl border'>
            <pre className='max-h-[70vh] overflow-auto p-4 text-xs leading-relaxed'>
              {data.content}
            </pre>
          </TabsContent>
        </Tabs>
      )}

      {!queryId && (
        <p className='text-sm text-muted-foreground'>
          Enter a query ID or open a query from the Queries page.
        </p>
      )}
    </div>
  )
}
