import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import {
  Activity,
  Crown,
  Cpu,
  Database,
  Server,
  Users,
} from 'lucide-react'
import { apiFetch } from '@/lib/api'
import { formatBytes, formatDuration } from '@/lib/format'
import {
  queryTypeTone,
  roleTone,
  stateTone,
} from '@/lib/states'
import type { Backend, Overview, QueryList, SessionRow } from '@/lib/types'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Donut, Gauge, MeterBar } from '@/components/mini-chart'
import { StateBadge } from '@/components/state-badge'
import { StatCard } from '@/components/stat-card'
import { AutoRefresh } from '@/components/auto-refresh'
import { PageHeader } from '@/components/page-header'

function OverviewPage() {
  const [auto, setAuto] = useState(false)
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['overview'],
    queryFn: () => apiFetch<Overview>('/api/web/v1/overview'),
    refetchInterval: auto ? 10000 : false,
  })
  const { data: backends } = useQuery({
    queryKey: ['backends'],
    queryFn: () => apiFetch<{ backends: Backend[] }>('/api/web/v1/backends'),
    refetchInterval: auto ? 10000 : false,
  })
  const { data: queries } = useQuery({
    queryKey: ['queries'],
    queryFn: () => apiFetch<QueryList>('/api/web/v1/queries'),
    refetchInterval: auto ? 10000 : false,
  })
  const { data: sessions } = useQuery({
    queryKey: ['sessions'],
    queryFn: () => apiFetch<{ sessions: SessionRow[] }>('/api/web/v1/sessions'),
    refetchInterval: auto ? 10000 : false,
  })

  const backendRows = backends?.backends ?? []
  const resources = useMemo(() => aggregateResources(backendRows), [backendRows])
  const workload = useMemo(() => aggregateQueries(queries), [queries])
  const sessionSplit = useMemo(
    () => aggregateSessions(sessions?.sessions ?? []),
    [sessions?.sessions]
  )

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Overview'
        description='Cluster status and workload at a glance.'
        actions={
          <div className='flex items-center gap-2'>
            {data && (
              <StateBadge state={data.fe.type} tone={roleTone(data.fe.type)} />
            )}
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

      {isLoading || !data ? (
        <div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-4'>
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className='h-32 rounded-xl' />
          ))}
        </div>
      ) : (
        <>
          <div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-6'>
            <div className='xl:col-span-2'>
              <StatCard
                title='Frontend'
                icon={<Crown />}
                tone={roleTone(data.fe.type)}
                value={data.fe.type}
                detail={
                  data.fe.leader ? 'Leader is serving metadata' : 'Follower'
                }
              />
            </div>
            <div className='xl:col-span-2'>
              <StatCard
                title='Backends'
                icon={<Server />}
                tone={
                  data.backends.total === 0
                    ? 'neutral'
                    : data.backends.alive === data.backends.total
                      ? 'green'
                      : 'amber'
                }
                value={`${data.backends.alive} / ${data.backends.total}`}
                detail={`${data.backends.total - data.backends.alive} not alive`}
              />
            </div>
            <div className='xl:col-span-2'>
              <StatCard
                title='Compute nodes'
                icon={<Cpu />}
                tone={
                  data.computeNodes.total === 0
                    ? 'neutral'
                    : data.computeNodes.alive === data.computeNodes.total
                      ? 'green'
                      : 'amber'
                }
                value={`${data.computeNodes.alive} / ${data.computeNodes.total}`}
                detail={`${data.computeNodes.total - data.computeNodes.alive} not alive`}
              />
            </div>
            <div className='xl:col-span-2'>
              <StatCard
                title='Sessions'
                icon={<Users />}
                tone='blue'
                value={String(data.sessions.total)}
                detail='Connected clients'
              />
            </div>
            <div className='xl:col-span-2'>
              <StatCard
                title='Running queries'
                icon={<Activity />}
                tone={data.queries.running > 0 ? 'sky' : 'neutral'}
                value={String(data.queries.running)}
                detail='Currently executing'
              />
            </div>
            <div className='xl:col-span-2'>
              <StatCard
                title='Finished queries'
                icon={<Database />}
                tone='teal'
                value={String(data.queries.finished)}
                detail='Retained in profile manager'
              />
            </div>
          </div>

          <div className='mt-4 grid gap-4 lg:grid-cols-[1.15fr_1.45fr_1fr]'>
            <Card>
              <CardHeader>
                <CardTitle>Cluster resources</CardTitle>
                <CardDescription>
                  Averages across {resources.count} backend
                  {resources.count === 1 ? '' : 's'}.
                </CardDescription>
              </CardHeader>
              <CardContent className='space-y-4'>
                <div className='flex justify-around'>
                  <Gauge value={resources.cpuPct} label='CPU' tone='blue' />
                  <Gauge
                    value={resources.memPct}
                    label='Memory'
                    tone='violet'
                  />
                  <Gauge
                    value={resources.diskPct}
                    label='Disk'
                    tone={resources.diskPct > 80 ? 'red' : 'amber'}
                  />
                </div>
                <div className='space-y-3'>
                  <MeterBar
                    value={resources.capacityUsed}
                    max={resources.capacityTotal}
                    tone='teal'
                    label='Capacity'
                    right={`${formatBytes(resources.capacityUsed)} / ${formatBytes(resources.capacityTotal)}`}
                  />
                  <MeterBar
                    value={resources.memUsed}
                    max={resources.memTotal}
                    tone='violet'
                    label='Memory'
                    right={`${formatBytes(resources.memUsed)} / ${formatBytes(resources.memTotal)}`}
                  />
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Workload</CardTitle>
                <CardDescription>
                  {workload.runningCount} running ·{' '}
                  {workload.finishedCount} finished
                </CardDescription>
              </CardHeader>
              <CardContent className='space-y-4'>
                <Donut
                  slices={workload.byType}
                  center={
                    <div className='text-center'>
                      <div className='text-lg font-semibold tabular-nums'>
                        {workload.runningCount}
                      </div>
                      <div className='text-[11px] text-muted-foreground'>
                        running
                      </div>
                    </div>
                  }
                />
                {workload.topQueries.length > 0 && (
                  <div className='space-y-1.5 border-t pt-3'>
                    <div className='text-[11px] font-medium tracking-wide text-muted-foreground uppercase'>
                      Slowest running
                    </div>
                    {workload.topQueries.map((query) => (
                      <Link
                        key={query.queryId}
                        to='/query-profile'
                        search={{ queryId: query.queryId }}
                        className='flex items-center gap-2 text-xs hover:underline'
                      >
                        <span className='truncate font-mono text-muted-foreground'>
                          {query.queryId}
                        </span>
                        <span className='ml-auto font-medium tabular-nums'>
                          {formatDuration(query.execTime ?? 0)}
                        </span>
                      </Link>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Session activity</CardTitle>
                <CardDescription>
                  {sessionSplit.total} active session
                  {sessionSplit.total === 1 ? '' : 's'} by command
                </CardDescription>
              </CardHeader>
              <CardContent>
                {sessionSplit.slices.length === 0 ? (
                  <p className='text-sm text-muted-foreground'>
                    No active sessions.
                  </p>
                ) : (
                  <Donut
                    slices={sessionSplit.slices}
                    center={
                      <div className='text-center'>
                        <div className='text-lg font-semibold tabular-nums'>
                          {sessionSplit.total}
                        </div>
                        <div className='text-[11px] text-muted-foreground'>
                          sessions
                        </div>
                      </div>
                    }
                  />
                )}
              </CardContent>
            </Card>
          </div>

          {backendRows.length > 0 && (
            <Card className='mt-4'>
              <CardHeader>
                <CardTitle>Backend health</CardTitle>
                <CardDescription>
                  Per-node resource usage reported by the last heartbeat.
                </CardDescription>
              </CardHeader>
              <CardContent className='grid gap-3 sm:grid-cols-2 xl:grid-cols-3'>
                {backendRows.map((backend) => (
                  <div
                    key={backend.id}
                    className='rounded-lg border p-3'
                  >
                    <div className='flex items-center justify-between gap-2'>
                      <span className='truncate text-sm font-medium'>
                        {backend.host}
                      </span>
                      <StateBadge
                        state={backend.state}
                        tone={stateTone(backend.state)}
                      />
                    </div>
                    <div className='mt-2 space-y-2'>
                      <MeterBar
                        value={backend.cpuUsedPermille / 10}
                        max={100}
                        tone='blue'
                        label='CPU'
                        right={`${(backend.cpuUsedPermille / 10).toFixed(1)}%`}
                      />
                      <MeterBar
                        value={backend.memUsedPct}
                        max={100}
                        tone='violet'
                        label='Mem'
                        right={`${backend.memUsedPct.toFixed(1)}%`}
                      />
                      <MeterBar
                        value={backend.maxDiskUsedPct}
                        max={100}
                        tone={backend.maxDiskUsedPct > 80 ? 'red' : 'amber'}
                        label='Disk'
                        right={`${backend.maxDiskUsedPct.toFixed(1)}%`}
                      />
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>
          )}

          {data.hardware?.enabled && (
            <Card className='mt-4'>
              <CardHeader>
                <CardTitle>Hardware</CardTitle>
                <CardDescription>
                  Host resources reported by the frontend.
                </CardDescription>
              </CardHeader>
              <CardContent className='grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-3'>
                <div>
                  <p className='text-xs text-muted-foreground'>OS</p>
                  <p>
                    {data.hardware.os?.family} {data.hardware.os?.version}
                  </p>
                </div>
                <div>
                  <p className='text-xs text-muted-foreground'>CPU</p>
                  <p>
                    {data.hardware.cpu?.physicalCores} physical /{' '}
                    {data.hardware.cpu?.logicalCores} logical
                  </p>
                </div>
                <div>
                  <p className='text-xs text-muted-foreground'>Memory</p>
                  <p>
                    {formatBytes(data.hardware.memory?.available ?? 0)} free of{' '}
                    {formatBytes(data.hardware.memory?.total ?? 0)}
                  </p>
                </div>
                {(data.hardware.disks ?? []).map((disk) => (
                  <div key={`${disk.mount}-${disk.name}`}>
                    <p className='text-xs text-muted-foreground'>
                      {disk.mount}
                    </p>
                    <p>
                      {formatBytes(disk.usable)} free of{' '}
                      {formatBytes(disk.total)}
                    </p>
                  </div>
                ))}
              </CardContent>
            </Card>
          )}

          <Card className='mt-4'>
            <CardHeader>
              <CardTitle>Version</CardTitle>
              <CardDescription>
                Build information reported by the frontend.
              </CardDescription>
            </CardHeader>
            <CardContent className='grid gap-1 text-sm text-muted-foreground'>
              <p>
                <span className='font-medium text-foreground'>Version:</span>{' '}
                {data.version.version}
              </p>
              <p>
                <span className='font-medium text-foreground'>Commit:</span>{' '}
                <code className='text-xs'>{data.version.commit}</code>
              </p>
              <p>
                <span className='font-medium text-foreground'>Build time:</span>{' '}
                {data.version.buildTime}
              </p>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

function aggregateResources(backends: Backend[]) {
  const count = backends.length
  if (count === 0) {
    return {
      count: 0,
      cpuPct: 0,
      memPct: 0,
      diskPct: 0,
      capacityUsed: 0,
      capacityTotal: 0,
      memUsed: 0,
      memTotal: 0,
    }
  }
  const sum = backends.reduce(
    (acc, backend) => ({
      cpu: acc.cpu + backend.cpuUsedPermille,
      mem: acc.mem + backend.memUsedPct,
      disk: acc.disk + backend.maxDiskUsedPct,
      capacityUsed: acc.capacityUsed + backend.dataUsedCapacityB,
      capacityTotal: acc.capacityTotal + backend.totalCapacityB,
      memUsed: acc.memUsed + backend.memUsedBytes,
      memTotal: acc.memTotal + backend.memLimitBytes,
    }),
    {
      cpu: 0,
      mem: 0,
      disk: 0,
      capacityUsed: 0,
      capacityTotal: 0,
      memUsed: 0,
      memTotal: 0,
    }
  )
  return {
    count,
    cpuPct: sum.cpu / count / 10,
    memPct: sum.mem / count,
    diskPct: sum.disk / count,
    capacityUsed: sum.capacityUsed,
    capacityTotal: sum.capacityTotal,
    memUsed: sum.memUsed,
    memTotal: sum.memTotal,
  }
}

function aggregateQueries(queries?: QueryList) {
  const running = queries?.running ?? []
  const finished = queries?.finished ?? []
  const counts = new Map<string, number>()
  for (const query of running) {
    const type = query.queryType || 'OTHER'
    counts.set(type, (counts.get(type) ?? 0) + 1)
  }
  const byType = [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([label, value]) => ({
      label,
      value,
      tone: queryTypeTone(label),
    }))
  const topQueries = [...running]
    .sort((a, b) => (b.execTime ?? 0) - (a.execTime ?? 0))
    .slice(0, 5)
  return {
    runningCount: running.length,
    finishedCount: finished.length,
    byType,
    topQueries,
  }
}

function aggregateSessions(sessions: SessionRow[]) {
  const counts = new Map<string, number>()
  for (const session of sessions) {
    const command = session.command || 'UNKNOWN'
    counts.set(command, (counts.get(command) ?? 0) + 1)
  }
  const tones = ['blue', 'sky', 'teal', 'violet', 'amber', 'orange']
  const slices = [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([label, value], index) => ({
      label,
      value,
      tone: tones[index % tones.length],
    }))
  return { total: sessions.length, slices }
}

export default OverviewPage
