import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { apiFetch } from '@/lib/api'
import { formatBytes, formatCpu, formatPercent } from '@/lib/format'
import type { Backend } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { stateTone } from '@/lib/states'
import { StateBadge } from '@/components/state-badge'
import { AutoRefresh } from '@/components/auto-refresh'
import { PageHeader } from '@/components/page-header'

const PAGE_SIZE = 50

function BackendsPage() {
  const [auto, setAuto] = useState(false)
  const navigate = useNavigate() as unknown as (options: {
    search: (previous: Record<string, unknown>) => Record<string, unknown>
    replace?: boolean
  }) => void
  const search = useSearch({ strict: false }) as {
    filter?: string
    page?: number
  }
  const filter = search.filter ?? ''
  const page = Math.max(0, Number(search.page ?? 0))
  const setFilter = (value: string) => {
    void navigate({
      search: (previous) => ({
        ...previous,
        filter: value || undefined,
        page: undefined,
      }),
      replace: true,
    })
  }
  const setPage = (value: number) => {
    void navigate({
      search: (previous) => ({
        ...previous,
        page: value > 0 ? value : undefined,
      }),
      replace: true,
    })
  }
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['backends'],
    queryFn: () => apiFetch<{ backends: Backend[] }>('/api/web/v1/backends'),
    refetchInterval: auto ? 10000 : false,
  })

  const filtered = useMemo(() => {
    const keyword = filter.trim().toLowerCase()
    const rows = data?.backends ?? []
    if (!keyword) return rows
    return rows.filter((backend) =>
      [String(backend.id), backend.host, backend.state, backend.version]
        .join(' ')
        .toLowerCase()
        .includes(keyword)
    )
  }, [data, filter])
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const current = Math.min(page, pageCount - 1)
  const rows = filtered.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE)

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Backends'
        description='Storage and compute backends registered in the cluster.'
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

      <div className='mb-3 flex flex-wrap items-center justify-between gap-2'>
        <Input
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          placeholder='Filter by host, id, state or version'
          className='max-w-sm'
        />
        <p className='text-xs text-muted-foreground'>
          {filtered.length} backends
        </p>
      </div>

      {isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <div className='wc-fade overflow-x-auto rounded-xl border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Host</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Ports</TableHead>
                <TableHead>CPU</TableHead>
                <TableHead>Memory</TableHead>
                <TableHead>Disk</TableHead>
                <TableHead>Version</TableHead>
                <TableHead>Last report</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((backend) => (
                <TableRow key={backend.id}>
                  <TableCell>
                    <div className='font-medium'>{backend.host}</div>
                    <div className='text-xs text-muted-foreground'>
                      id {backend.id} · started {backend.startTime}
                    </div>
                  </TableCell>
                  <TableCell>
                    <StateBadge
                      state={backend.state}
                      tone={backend.alive ? stateTone(backend.state) : 'red'}
                    />
                  </TableCell>
                  <TableCell className='text-xs text-muted-foreground'>
                    <div>heartbeat {backend.heartbeatPort}</div>
                    <div>
                      be {backend.bePort} · http {backend.httpPort}
                    </div>
                    <div>brpc {backend.brpcPort}</div>
                  </TableCell>
                  <TableCell>
                    <div>{formatCpu(backend.cpuUsedPermille)}</div>
                    <div className='text-xs text-muted-foreground'>
                      {backend.cpuCores} cores
                    </div>
                  </TableCell>
                  <TableCell>
                    <div>
                      {formatBytes(backend.memUsedBytes)} /{' '}
                      {formatBytes(backend.memLimitBytes)}
                    </div>
                    <div className='text-xs text-muted-foreground'>
                      {formatPercent(backend.memUsedPct)}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div>{formatBytes(backend.dataUsedCapacityB)} used</div>
                    <div className='text-xs text-muted-foreground'>
                      {formatBytes(backend.availableCapacityB)} free · max{' '}
                      {formatPercent(backend.maxDiskUsedPct)}
                    </div>
                  </TableCell>
                  <TableCell className='text-xs text-muted-foreground'>
                    {backend.version || '-'}
                  </TableCell>
                  <TableCell className='text-xs text-muted-foreground'>
                    {backend.lastSuccessReportTabletsTime || '-'}
                  </TableCell>
                </TableRow>
              ))}
              {rows.length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={8}
                    className='h-24 text-center text-muted-foreground'
                  >
                    No backends registered.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      )}

      {filtered.length > PAGE_SIZE && (
        <div className='mt-3 flex items-center justify-end gap-2'>
          <span className='text-xs text-muted-foreground'>
            Page {current + 1} of {pageCount}
          </span>
          <Button
            variant='outline'
            size='sm'
            disabled={current === 0}
            onClick={() => setPage(current - 1)}
          >
            Previous
          </Button>
          <Button
            variant='outline'
            size='sm'
            disabled={current >= pageCount - 1}
            onClick={() => setPage(current + 1)}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  )
}

export default BackendsPage
