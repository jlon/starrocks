import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import { formatTimestamp } from '@/lib/format'
import type { HaInfo } from '@/lib/types'
import { BookMarked, BookOpenCheck, Crown, History } from 'lucide-react'
import { roleTone } from '@/lib/states'
import { StateBadge, StateChip } from '@/components/state-badge'
import { StatCard } from '@/components/stat-card'
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
import { AutoRefresh } from '@/components/auto-refresh'
import { PageHeader } from '@/components/page-header'

function HaPage() {
  const [auto, setAuto] = useState(false)
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['ha'],
    queryFn: () => apiFetch<HaInfo>('/api/web/v1/ha'),
    refetchInterval: auto ? 10000 : false,
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='High Availability'
        description='Frontend role, journal, checkpoint and cluster membership.'
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

      {isLoading || !data ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <div className='space-y-4'>
          <div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-4'>
            <StatCard
              title='Role'
              value={data.feType}
              icon={<Crown />}
              tone={roleTone(data.feType)}
              detail={data.leader ? 'Serving metadata' : 'Replay only'}
            />
            <StatCard
              title='Journal ID'
              value={String(data.journalId)}
              icon={<BookMarked />}
              tone='blue'
              detail={data.canRead ? 'Journal readable' : 'Journal not readable'}
            />
            <StatCard
              title='Can read'
              value={data.canRead ? 'Yes' : 'No'}
              icon={<BookOpenCheck />}
              tone={data.canRead ? 'green' : 'amber'}
            />
            <StatCard
              title='Last checkpoint'
              icon={<History />}
              tone='neutral'
              value={
                data.checkpoint ? String(data.checkpoint.version) : '–'
              }
              detail={
                data.checkpoint
                  ? formatTimestamp(data.checkpoint.time)
                  : 'No checkpoint recorded'
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
                      <TableRow
                        key={`${frontend.host}:${frontend.editLogPort}`}
                      >
                        <TableCell>{frontend.name}</TableCell>
                        <TableCell className='font-mono text-xs'>
                          {frontend.host}
                        </TableCell>
                        <TableCell>
                          <StateChip tone={roleTone(frontend.role)}>
                            {frontend.role}
                          </StateChip>
                        </TableCell>
                        <TableCell>
                          <StateBadge
                            state={frontend.alive ? 'alive' : 'dead'}
                            tone={frontend.alive ? 'green' : 'red'}
                          />
                        </TableCell>
                        <TableCell>{frontend.queryPort}</TableCell>
                        <TableCell>{frontend.rpcPort}</TableCell>
                        <TableCell>{frontend.editLogPort}</TableCell>
                        <TableCell>{frontend.replayedJournalId}</TableCell>
                        <TableCell className='text-xs text-muted-foreground'>
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
                  <StateChip key={name} tone='blue'>
                    {name}
                  </StateChip>
                ))}
                {(data.databases ?? []).length === 0 && (
                  <span className='text-sm text-muted-foreground'>None</span>
                )}
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>Removed frontends</CardTitle>
                <CardDescription>
                  Frontends removed from the cluster.
                </CardDescription>
              </CardHeader>
              <CardContent className='flex flex-wrap gap-2'>
                {(data.removedFrontends ?? []).map((name) => (
                  <StateChip key={name} tone='amber'>
                    {name}
                  </StateChip>
                ))}
                {(data.removedFrontends ?? []).length === 0 && (
                  <span className='text-sm text-muted-foreground'>None</span>
                )}
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </div>
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
      <CardContent className='flex flex-wrap gap-2'>
        {nodes.map((node) => (
          <StateChip key={`${node.host}:${node.port}`} tone='violet'>
            {node.host}:{node.port}
          </StateChip>
        ))}
        {nodes.length === 0 && (
          <p className='text-sm text-muted-foreground'>None</p>
        )}
      </CardContent>
    </Card>
  )
}

export default HaPage
