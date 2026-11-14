import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Download } from 'lucide-react'
import { apiFetch } from '@/lib/api'
import { downloadCsv } from '@/lib/csv'
import { formatDuration, formatTimestamp } from '@/lib/format'
import type { QueryList, QueryRow } from '@/lib/types'
import { Activity } from 'lucide-react'
import { queryTypeTone, stateTone } from '@/lib/states'
import { CopyButton } from '@/components/copy-button'
import { EmptyState } from '@/components/empty-state'
import { StateBadge, StateChip } from '@/components/state-badge'
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
import { AutoRefresh } from '@/components/auto-refresh'
import { PageHeader } from '@/components/page-header'

function QueriesPage() {
  const [auto, setAuto] = useState(false)
  const [tab, setTab] = useState('running')
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['queries'],
    queryFn: () => apiFetch<QueryList>('/api/web/v1/queries'),
    refetchInterval: auto ? 10000 : false,
  })

  const sortedRunning = useMemo(
    () =>
      [...(data?.running ?? [])].sort(
        (left, right) => (right.execTime ?? 0) - (left.execTime ?? 0)
      ),
    [data?.running]
  )
  const sortedFinished = useMemo(
    () =>
      [...(data?.finished ?? [])].sort((left, right) =>
        String(right.endTime ?? '').localeCompare(String(left.endTime ?? ''))
      ),
    [data?.finished]
  )
  const rows = tab === 'running' ? sortedRunning : sortedFinished
  const headers = runningHeaders(tab)
  const download = () =>
    downloadCsv(
      `queries-${tab}-${new Date().toISOString().slice(0, 19)}.csv`,
      headers,
      rows.map((row) =>
        tab === 'running'
          ? [
              row.queryId,
              row.user,
              row.db,
              row.sql,
              row.queryType,
              row.state,
              row.execTime,
              row.startTime,
            ]
          : [
              row.queryId,
              row.user,
              row.db,
              row.sql,
              row.queryType,
              row.state,
              row.totalTime,
              row.warehouse,
            ]
      )
    )

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Queries'
        description='Running and recently finished queries on this frontend.'
        actions={
          <div className='flex items-center gap-2'>
            <Button variant='outline' size='sm' onClick={download}>
              <Download /> CSV
            </Button>
            <AutoRefresh
              enabled={auto}
              onChange={setAuto}
              onRefresh={() => void refetch()}
              pending={isFetching}
            />
          </div>
        }
      />

      {error && (
        <p className='mb-4 text-sm text-destructive'>{error.message}</p>
      )}

      {isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList>
            <TabsTrigger value='running'>
              Running ({data?.running.length ?? 0})
            </TabsTrigger>
            <TabsTrigger value='finished'>
              Finished ({data?.finished.length ?? 0})
            </TabsTrigger>
          </TabsList>

          <TabsContent value='running'>
            <QueryTable rows={sortedRunning} running />
          </TabsContent>
          <TabsContent value='finished'>
            <QueryTable rows={sortedFinished} />
          </TabsContent>
        </Tabs>
      )}
    </div>
  )
}

function runningHeaders(tab: string): string[] {
  return tab === 'running'
    ? [
        'Query ID',
        'User',
        'Database',
        'SQL',
        'Type',
        'State',
        'Elapsed',
        'Started',
      ]
    : [
        'Query ID',
        'User',
        'Database',
        'SQL',
        'Type',
        'State',
        'Total',
        'Warehouse',
      ]
}

type QueryTableProps = {
  rows: QueryRow[]
  running?: boolean
}

function QueryTable({ rows, running }: QueryTableProps) {
  return (
    <div className='wc-fade mt-2 max-h-[calc(100vh-280px)] overflow-auto bg-card rounded-xl border'>
      <Table>
        <TableHeader className='sticky top-0 z-10 bg-card'>
          <TableRow>
            <TableHead className='whitespace-nowrap'>Query ID</TableHead>
            <TableHead className='whitespace-nowrap'>User</TableHead>
            <TableHead className='whitespace-nowrap'>Database</TableHead>
            <TableHead>SQL</TableHead>
            <TableHead className='whitespace-nowrap'>Type</TableHead>
            <TableHead className='whitespace-nowrap'>State</TableHead>
            <TableHead className='whitespace-nowrap'>
              {running ? 'Elapsed' : 'Total'}
            </TableHead>
            <TableHead className='whitespace-nowrap'>
              {running ? 'Started' : 'Warehouse'}
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.queryId}>
              <TableCell className='font-mono text-xs'>
                <span className='group inline-flex items-center gap-0.5'>
                  <Link
                    to='/query-profile'
                    search={{ queryId: row.queryId }}
                    className='hover:underline'
                  >
                    {row.queryId}
                  </Link>
                  <CopyButton text={row.queryId} />
                </span>
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
                <StateChip tone={queryTypeTone(row.queryType)}>
                  {row.queryType || '-'}
                </StateChip>
              </TableCell>
              <TableCell>
                <StateBadge state={row.state} tone={stateTone(row.state)} />
              </TableCell>
              <TableCell className='text-xs text-muted-foreground'>
                {running ? formatDuration(row.execTime) : row.totalTime || '-'}
              </TableCell>
              <TableCell className='text-xs text-muted-foreground'>
                {running
                  ? formatTimestamp(row.startTime)
                  : row.warehouse || '-'}
              </TableCell>
            </TableRow>
          ))}
          {rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={8}>
                <EmptyState
                  icon={Activity}
                  title={running ? 'No running queries' : 'No finished queries'}
                  description={
                    running
                      ? 'No queries are executing on this frontend right now.'
                      : 'No recently finished queries are retained.'
                  }
                />
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  )
}

export default QueriesPage
