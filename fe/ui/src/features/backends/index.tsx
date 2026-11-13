import { useQuery } from '@tanstack/react-query'
import { PageHeader } from '@/components/page-header'
import { Badge } from '@/components/ui/badge'
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
import { formatBytes, formatCpu, formatPercent } from '@/lib/format'
import type { Backend } from '@/lib/types'

function BackendsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['backends'],
    queryFn: () => apiFetch<{ backends: Backend[] }>('/api/web/v1/backends'),
    refetchInterval: 5000,
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Backends'
        description='Storage and compute backends registered in the cluster.'
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
              {(data?.backends ?? []).map((backend) => (
                <TableRow key={backend.id}>
                  <TableCell>
                    <div className='font-medium'>{backend.host}</div>
                    <div className='text-muted-foreground text-xs'>
                      id {backend.id} · started {backend.startTime}
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant={backend.alive ? 'default' : 'destructive'}>
                      {backend.state}
                    </Badge>
                  </TableCell>
                  <TableCell className='text-muted-foreground text-xs'>
                    <div>heartbeat {backend.heartbeatPort}</div>
                    <div>
                      be {backend.bePort} · http {backend.httpPort}
                    </div>
                    <div>brpc {backend.brpcPort}</div>
                  </TableCell>
                  <TableCell>
                    <div>{formatCpu(backend.cpuUsedPermille)}</div>
                    <div className='text-muted-foreground text-xs'>
                      {backend.cpuCores} cores
                    </div>
                  </TableCell>
                  <TableCell>
                    <div>
                      {formatBytes(backend.memUsedBytes)} /{' '}
                      {formatBytes(backend.memLimitBytes)}
                    </div>
                    <div className='text-muted-foreground text-xs'>
                      {formatPercent(backend.memUsedPct)}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div>{formatBytes(backend.dataUsedCapacityB)} used</div>
                    <div className='text-muted-foreground text-xs'>
                      {formatBytes(backend.availableCapacityB)} free · max{' '}
                      {formatPercent(backend.maxDiskUsedPct)}
                    </div>
                  </TableCell>
                  <TableCell className='text-muted-foreground text-xs'>
                    {backend.version || '-'}
                  </TableCell>
                  <TableCell className='text-muted-foreground text-xs'>
                    {backend.lastSuccessReportTabletsTime || '-'}
                  </TableCell>
                </TableRow>
              ))}
              {(data?.backends ?? []).length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={8}
                    className='text-muted-foreground h-24 text-center'
                  >
                    No backends registered.
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

export default BackendsPage
