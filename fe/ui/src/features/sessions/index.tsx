import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/confirm-dialog'
import { PageHeader } from '@/components/page-header'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { apiFetch } from '@/lib/api'
import type { SessionRow } from '@/lib/types'

function SessionsPage() {
  const queryClient = useQueryClient()
  const [killId, setKillId] = useState<string | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['sessions'],
    queryFn: () =>
      apiFetch<{ sessions: SessionRow[] }>('/api/web/v1/sessions'),
    refetchInterval: 5000,
  })

  const kill = useMutation({
    mutationFn: (connectionId: string) =>
      apiFetch(
        `/api/web/v1/sessions/${encodeURIComponent(connectionId)}/kill`,
        { method: 'POST' }
      ),
    onSuccess: () => {
      toast.success('Session killed')
      setKillId(null)
      queryClient.invalidateQueries({ queryKey: ['sessions'] })
    },
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Sessions'
        description='Client connections currently registered on this frontend.'
      />

      {error && (
        <p className='text-destructive mb-4 text-sm'>{error.message}</p>
      )}

      {isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <div className='overflow-x-auto rounded-xl border'>
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
                <TableHead className='text-right'>Action</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.sessions ?? []).map((session) => (
                <TableRow key={session.id}>
                  <TableCell className='font-mono text-xs'>
                    {session.id}
                  </TableCell>
                  <TableCell>{session.user}</TableCell>
                  <TableCell className='text-muted-foreground text-xs'>
                    {session.host}
                  </TableCell>
                  <TableCell className='text-muted-foreground'>
                    {session.db || '-'}
                  </TableCell>
                  <TableCell>{session.command}</TableCell>
                  <TableCell className='text-muted-foreground text-xs'>
                    {session.time}
                  </TableCell>
                  <TableCell>
                    <Badge variant={session.running ? 'default' : 'secondary'}>
                      {session.state || '-'}
                    </Badge>
                  </TableCell>
                  <TableCell
                    className='max-w-[420px] truncate font-mono text-xs'
                    title={session.info}
                  >
                    {session.info || '-'}
                  </TableCell>
                  <TableCell className='text-muted-foreground text-xs'>
                    {session.warehouse || '-'}
                  </TableCell>
                  <TableCell className='text-right'>
                    <Button
                      variant='destructive'
                      size='sm'
                      onClick={() => setKillId(session.id)}
                    >
                      Kill
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {(data?.sessions ?? []).length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={10}
                    className='text-muted-foreground h-24 text-center'
                  >
                    No active sessions.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      )}

      <ConfirmDialog
        open={killId !== null}
        onOpenChange={(open) => !open && setKillId(null)}
        title='Kill session'
        desc={`Kill connection ${killId ?? ''}? The client will be disconnected.`}
        confirmText='Kill session'
        destructive
        isLoading={kill.isPending}
        handleConfirm={() => killId && kill.mutate(killId)}
      />
    </div>
  )
}

export default SessionsPage
