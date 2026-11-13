import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { SessionRow } from '@/lib/types'
import { StateBadge } from '@/components/state-badge'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { AutoRefresh } from '@/components/auto-refresh'
import { PageHeader } from '@/components/page-header'

function SessionsPage() {
  const [auto, setAuto] = useState(false)
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['sessions'],
    queryFn: () => apiFetch<{ sessions: SessionRow[] }>('/api/web/v1/sessions'),
    refetchInterval: auto ? 10000 : false,
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Sessions'
        description='Client connections currently registered on this frontend.'
        actions={
          <AutoRefresh
            enabled={auto}
            onChange={setAuto}
            onRefresh={() => void refetch()}
            pending={isFetching}
          />
        }
      />

      {error && (
        <p className='mb-4 text-sm text-destructive'>{error.message}</p>
      )}

      {isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <div className='wc-fade overflow-x-auto rounded-xl border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>User</TableHead>
                <TableHead>Host</TableHead>
                <TableHead>Database</TableHead>
                <TableHead>Command</TableHead>
                <TableHead>Time</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Info</TableHead>
                <TableHead>Warehouse</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.sessions ?? []).map((session) => (
                <TableRow key={session.id}>
                  <TableCell className='font-mono text-xs'>
                    {session.id}
                  </TableCell>
                  <TableCell>{session.user}</TableCell>
                  <TableCell className='text-xs text-muted-foreground'>
                    {session.host}
                  </TableCell>
                  <TableCell className='text-muted-foreground'>
                    {session.db || '-'}
                  </TableCell>
                  <TableCell>{session.command}</TableCell>
                  <TableCell className='text-xs text-muted-foreground'>
                    {session.time}
                  </TableCell>
                  <TableCell>
                    <StateBadge
                      state={session.state}
                      tone={session.running ? 'sky' : 'neutral'}
                    />
                  </TableCell>
                  <TableCell
                    className='max-w-[420px] truncate font-mono text-xs'
                    title={session.info}
                  >
                    {session.info || '-'}
                  </TableCell>
                  <TableCell className='text-xs text-muted-foreground'>
                    {session.warehouse || '-'}
                  </TableCell>
                </TableRow>
              ))}
              {(data?.sessions ?? []).length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={9}
                    className='h-24 text-center text-muted-foreground'
                  >
                    No active sessions.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}

export default SessionsPage
