import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { apiFetch } from '@/lib/api'
import { formatDuration, formatTimestamp } from '@/lib/format'
import type { QueryList, QueryRow } from '@/lib/types'

function QueriesPage() {
  const queryClient = useQueryClient()
  const [cancelId, setCancelId] = useState<string | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['queries'],
    queryFn: () => apiFetch<QueryList>('/api/web/v1/queries'),
    refetchInterval: 5000,
  })

  const cancel = useMutation({
    mutationFn: (queryId: string) =>
      apiFetch(`/api/web/v1/queries/${encodeURIComponent(queryId)}/cancel`, {
        method: 'POST',
      }),
    onSuccess: () => {
      toast.success('Query cancelled')
      setCancelId(null)
      queryClient.invalidateQueries({ queryKey: ['queries'] })
    },
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Queries'
        description='Running and recently finished queries on this frontend.'
      />

      {error && (
        <p className='text-destructive mb-4 text-sm'>{error.message}</p>
      )}

      {isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <Tabs defaultValue='running'>
          <TabsList>
            <TabsTrigger value='running'>
              Running ({data?.running.length ?? 0})
            </TabsTrigger>
            <TabsTrigger value='finished'>
              Finished ({data?.finished.length ?? 0})
            </TabsTrigger>
          </TabsList>

          <TabsContent value='running'>
            <QueryTable
              rows={data?.running ?? []}
              running
              onCancel={setCancelId}
            />
          </TabsContent>
          <TabsContent value='finished'>
            <QueryTable rows={data?.finished ?? []} />
          </TabsContent>
        </Tabs>
      )}

      <ConfirmDialog
        open={cancelId !== null}
        onOpenChange={(open) => !open && setCancelId(null)}
        title='Cancel query'
        desc={`Cancel query ${cancelId ?? ''}? This sends KILL QUERY to the running query.`}
        confirmText='Cancel query'
        destructive
        isLoading={cancel.isPending}
        handleConfirm={() => cancelId && cancel.mutate(cancelId)}
      />
    </div>
  )
}

type QueryTableProps = {
  rows: QueryRow[]
  running?: boolean
  onCancel?: (queryId: string) => void
}

function QueryTable({ rows, running, onCancel }: QueryTableProps) {
  return (
    <div className='mt-2 overflow-x-auto rounded-xl border'>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Query ID</TableHead>
            <TableHead>User</TableHead>
            <TableHead>Database</TableHead>
            <TableHead>SQL</TableHead>
            <TableHead>State</TableHead>
            <TableHead>{running ? 'Elapsed' : 'Total'}</TableHead>
            <TableHead>{running ? 'Started' : 'Warehouse'}</TableHead>
            {running && <TableHead className='text-right'>Action</TableHead>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.queryId}>
              <TableCell className='font-mono text-xs'>
                <Link
                  to='/query-profile'
                  search={{ queryId: row.queryId }}
                  className='hover:underline'
                >
                  {row.queryId}
                </Link>
              </TableCell>
              <TableCell>{row.user}</TableCell>
              <TableCell className='text-muted-foreground'>
                {row.db || '-'}
              </TableCell>
              <TableCell
                className='max-w-[420px] truncate font-mono text-xs'
                title={row.sql}
              >
                {row.sql || '-'}
              </TableCell>
              <TableCell>
                <Badge variant='outline'>{row.state || '-'}</Badge>
              </TableCell>
              <TableCell className='text-muted-foreground text-xs'>
                {running
                  ? formatDuration(row.execTime)
                  : row.totalTime || '-'}
              </TableCell>
              <TableCell className='text-muted-foreground text-xs'>
                {running
                  ? formatTimestamp(row.startTime)
                  : row.warehouse || '-'}
              </TableCell>
              {running && (
                <TableCell className='text-right'>
                  <Button
                    variant='destructive'
                    size='sm'
                    onClick={() => onCancel?.(row.queryId)}
                  >
                    Cancel
                  </Button>
                </TableCell>
              )}
            </TableRow>
          ))}
          {rows.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={running ? 8 : 7}
                className='text-muted-foreground h-24 text-center'
              >
                {running ? 'No running queries.' : 'No finished queries.'}
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  )
}

export default QueriesPage
