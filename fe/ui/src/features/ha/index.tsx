import { useQuery } from '@tanstack/react-query'
import { PageHeader } from '@/components/page-header'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
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
import { formatTimestamp } from '@/lib/format'
import type { HaInfo } from '@/lib/types'

function HaPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['ha'],
    queryFn: () => apiFetch<HaInfo>('/api/web/v1/ha'),
    refetchInterval: 5000,
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='High Availability'
        description='Frontend role, journal, checkpoint and cluster membership.'
        actions={
          data && (
            <Badge variant={data.leader ? 'default' : 'secondary'}>
              {data.feType}
            </Badge>
          )
        }
      />

      {error && (
        <p className='text-destructive mb-4 text-sm'>{error.message}</p>
      )}

      {isLoading || !data ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <div className='space-y-4'>
          <div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-4'>
            <Metric title='Role' value={data.feType} />
            <Metric title='Journal ID' value={String(data.journalId)} />
            <Metric title='Can read' value={data.canRead ? 'Yes' : 'No'} />
            <Metric
              title='Last checkpoint'
              value={
                data.checkpoint
                  ? `${data.checkpoint.version} · ${formatTimestamp(data.checkpoint.time)}`
                  : 'Unavailable'
              }
            />
          </div>

          <Card>
            <CardHeader>
              <CardTitle>Frontends</CardTitle>
              <CardDescription>Allowed frontend nodes.</CardDescription>
            </CardHeader>
            <CardContent>
              <div className='overflow-x-auto rounded-lg border'>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Name</TableHead>
                      <TableHead>Host</TableHead>
                      <TableHead>Role</TableHead>
                      <TableHead>Alive</TableHead>
                      <TableHead>Query</TableHead>
                      <TableHead>RPC</TableHead>
                      <TableHead>Edit log</TableHead>
                      <TableHead>Replayed</TableHead>
                      <TableHead>Version</TableHead>
                      <TableHead>Heap</TableHead>
                      <TableHead>CPU</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.frontends.map((frontend) => (
                      <TableRow key={`${frontend.host}:${frontend.editLogPort}`}>
                        <TableCell>{frontend.name}</TableCell>
                        <TableCell className='font-mono text-xs'>
                          {frontend.host}
                        </TableCell>
                        <TableCell>{frontend.role}</TableCell>
                        <TableCell>
                          <Badge
                            variant={frontend.alive ? 'default' : 'destructive'}
                          >
                            {frontend.alive ? 'Alive' : 'Dead'}
                          </Badge>
                        </TableCell>
                        <TableCell>{frontend.queryPort}</TableCell>
                        <TableCell>{frontend.rpcPort}</TableCell>
                        <TableCell>{frontend.editLogPort}</TableCell>
                        <TableCell>{frontend.replayedJournalId}</TableCell>
                        <TableCell className='text-muted-foreground text-xs'>
                          {frontend.version}
                        </TableCell>
                        <TableCell>
                          {frontend.heapUsedPercent.toFixed(1)}%
                        </TableCell>
                        <TableCell>{frontend.cpuCores}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>

          <div className='grid gap-4 lg:grid-cols-2'>
            <NodeCard title='Electable nodes' nodes={data.nodes.electable} />
            <NodeCard title='Observer nodes' nodes={data.nodes.observer} />
          </div>

          <div className='grid gap-4 lg:grid-cols-2'>
            <Card>
              <CardHeader>
                <CardTitle>Databases</CardTitle>
                <CardDescription>Journal database names.</CardDescription>
              </CardHeader>
              <CardContent className='flex flex-wrap gap-2'>
                {(data.databases ?? []).map((name) => (
                  <Badge key={name} variant='secondary'>
                    {name}
                  </Badge>
                ))}
                {(data.databases ?? []).length === 0 && (
                  <span className='text-muted-foreground text-sm'>None</span>
                )}
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>Removed frontends</CardTitle>
                <CardDescription>Frontends removed from the cluster.</CardDescription>
              </CardHeader>
              <CardContent className='flex flex-wrap gap-2'>
                {(data.removedFrontends ?? []).map((name) => (
                  <Badge key={name} variant='secondary'>
                    {name}
                  </Badge>
                ))}
                {(data.removedFrontends ?? []).length === 0 && (
                  <span className='text-muted-foreground text-sm'>None</span>
                )}
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </div>
  )
}

function Metric({ title, value }: { title: string; value: string }) {
  return (
    <Card>
      <CardHeader>
        <CardDescription>{title}</CardDescription>
        <CardTitle className='text-xl'>{value}</CardTitle>
      </CardHeader>
    </Card>
  )
}

function NodeCard({
  title,
  nodes,
}: {
  title: string
  nodes: { host: string; port: number }[]
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent className='space-y-1'>
        {nodes.map((node) => (
          <p key={`${node.host}:${node.port}`} className='font-mono text-sm'>
            {node.host}:{node.port}
          </p>
        ))}
        {nodes.length === 0 && (
          <p className='text-muted-foreground text-sm'>None</p>
        )}
      </CardContent>
    </Card>
  )
}

export default HaPage
