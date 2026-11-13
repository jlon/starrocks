import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, RefreshCw, X } from 'lucide-react'
import { toast } from 'sonner'
import { PageHeader } from '@/components/page-header'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { apiFetch } from '@/lib/api'
import { formatBytes, formatTimestamp } from '@/lib/format'
import type { LogContent, LogsInfo } from '@/lib/types'

function LogsPage() {
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState('')
  const [verbose, setVerbose] = useState('')

  const logs = useQuery({
    queryKey: ['logs'],
    queryFn: () => apiFetch<LogsInfo>('/api/web/v1/logs'),
  })
  const name = selected || logs.data?.files[0]?.name || ''
  const content = useQuery({
    queryKey: ['log', name],
    queryFn: () =>
      apiFetch<LogContent>(
        `/api/web/v1/logs/${encodeURIComponent(name)}`
      ),
    enabled: name.length > 0,
  })

  const updateVerbose = useMutation({
    mutationFn: ({ action, value }: { action: 'add' | 'remove'; value: string }) =>
      apiFetch('/api/web/v1/logs/verbose', {
        method: 'POST',
        body: JSON.stringify({ action, name: value }),
      }),
    onSuccess: () => {
      setVerbose('')
      toast.success('Verbose configuration updated')
      queryClient.invalidateQueries({ queryKey: ['logs'] })
    },
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Logs'
        description='Frontend log configuration and recent log contents.'
        actions={
          <Button variant='outline' size='sm' onClick={() => logs.refetch()}>
            <RefreshCw /> Refresh
          </Button>
        }
      />

      {logs.error && (
        <p className='text-destructive mb-4 text-sm'>{logs.error.message}</p>
      )}

      {logs.isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <div className='space-y-4'>
          <div className='flex flex-wrap items-center gap-4'>
            <Select value={name} onValueChange={setSelected}>
              <SelectTrigger className='w-72'>
                <SelectValue placeholder='Select a log file' />
              </SelectTrigger>
              <SelectContent>
                {(logs.data?.files ?? []).map((file) => (
                  <SelectItem key={file.name} value={file.name}>
                    {file.name} ({formatBytes(file.size)})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <span className='text-muted-foreground text-sm'>
              Level: <strong>{logs.data?.level}</strong>
            </span>
            <span className='text-muted-foreground text-sm'>
              Directory: <code className='text-xs'>{logs.data?.dir}</code>
            </span>
          </div>

          <div className='space-y-2'>
            <p className='text-sm font-medium'>Verbose names</p>
            <div className='flex flex-wrap items-center gap-2'>
              {(logs.data?.verboseNames ?? []).map((item) => (
                <Badge key={item} variant='secondary' className='gap-1'>
                  {item}
                  <button
                    type='button'
                    aria-label={`Remove ${item}`}
                    onClick={() =>
                      updateVerbose.mutate({ action: 'remove', value: item })
                    }
                  >
                    <X className='size-3' />
                  </button>
                </Badge>
              ))}
              <form
                className='flex gap-2'
                onSubmit={(event) => {
                  event.preventDefault()
                  if (verbose.trim()) {
                    updateVerbose.mutate({
                      action: 'add',
                      value: verbose.trim(),
                    })
                  }
                }}
              >
                <Input
                  value={verbose}
                  onChange={(event) => setVerbose(event.target.value)}
                  placeholder='com.example'
                  className='h-8 w-48'
                />
                <Button
                  type='submit'
                  size='sm'
                  variant='outline'
                  disabled={!verbose.trim() || updateVerbose.isPending}
                >
                  <Plus /> Add
                </Button>
              </form>
            </div>
          </div>

          {content.error && (
            <p className='text-destructive text-sm'>{content.error.message}</p>
          )}

          {content.isLoading ? (
            <Skeleton className='h-96 w-full rounded-xl' />
          ) : (
            content.data && (
              <div className='rounded-xl border'>
                <div className='text-muted-foreground flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2 text-xs'>
                  <span className='font-mono'>{content.data.name}</span>
                  <span>
                    {formatBytes(content.data.size)} ·{' '}
                    {formatTimestamp(
                      logs.data?.files.find((file) => file.name === name)
                        ?.modified
                    )}
                    {content.data.truncated && ' · showing last 1 MiB'}
                  </span>
                </div>
                <pre className='max-h-[65vh] overflow-auto p-4 text-xs leading-relaxed'>
                  {content.data.content}
                </pre>
              </div>
            )
          )}
        </div>
      )}
    </div>
  )
}

export default LogsPage
