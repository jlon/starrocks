import { type ReactNode, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Activity,
  CheckCircle2,
  Cpu,
  Database,
  Server,
  Users,
  XCircle,
} from 'lucide-react'
import { apiFetch } from '@/lib/api'
import { formatBytes } from '@/lib/format'
import type { Overview } from '@/lib/types'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { AutoRefresh } from '@/components/auto-refresh'
import { PageHeader } from '@/components/page-header'

function OverviewPage() {
  const [auto, setAuto] = useState(false)
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['overview'],
    queryFn: () => apiFetch<Overview>('/api/web/v1/overview'),
    refetchInterval: auto ? 10000 : false,
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Overview'
        description='Cluster status and workload at a glance.'
        actions={
          <div className='flex items-center gap-2'>
            {data && (
              <Badge variant={data.fe.leader ? 'default' : 'secondary'}>
                {data.fe.type}
              </Badge>
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
          <div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-4'>
            <StatCard
              title='Frontend'
              icon={
                data.fe.leader ? (
                  <CheckCircle2 className='text-emerald-500' />
                ) : (
                  <XCircle className='text-amber-500' />
                )
              }
              value={data.fe.type}
              detail={
                data.fe.leader ? 'Leader is serving metadata' : 'Follower'
              }
            />
            <StatCard
              title='Backends'
              icon={<Server />}
              value={`${data.backends.alive} / ${data.backends.total}`}
              detail={`${data.backends.total - data.backends.alive} not alive`}
            />
            <StatCard
              title='Compute nodes'
              icon={<Cpu />}
              value={`${data.computeNodes.alive} / ${data.computeNodes.total}`}
              detail={`${data.computeNodes.total - data.computeNodes.alive} not alive`}
            />
            <StatCard
              title='Sessions'
              icon={<Users />}
              value={String(data.sessions.total)}
              detail='Connected clients'
            />
            <StatCard
              title='Running queries'
              icon={<Activity />}
              value={String(data.queries.running)}
              detail='Currently executing'
            />
            <StatCard
              title='Finished queries'
              icon={<Database />}
              value={String(data.queries.finished)}
              detail='Retained in profile manager'
            />
          </div>

          {data.hardware?.enabled && (
            <Card className='mt-4'>
              <CardHeader>
                <CardTitle>Hardware</CardTitle>
                <CardDescription>
                  Host resources reported by the frontend.
                </CardDescription>
              </CardHeader>
              <CardContent className='grid gap-3 text-sm sm:grid-cols-3'>
                <div>
                  <p className='text-xs text-muted-foreground'>OS</p>
                  <p>
                    {data.hardware.os?.family} {data.hardware.os?.version}
                  </p>
                </div>
                <div>
                  <p className='text-xs text-muted-foreground'>CPU</p>
                  <p>{data.hardware.cpu?.name}</p>
                  <p className='text-xs text-muted-foreground'>
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

type StatCardProps = {
  title: string
  icon: ReactNode
  value: string
  detail: string
}

function StatCard({ title, icon, value, detail }: StatCardProps) {
  return (
    <Card>
      <CardHeader className='pb-2'>
        <CardDescription className='flex items-center gap-2'>
          <span className='[&>svg]:size-4'>{icon}</span>
          {title}
        </CardDescription>
        <CardTitle className='text-2xl'>{value}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className='text-xs text-muted-foreground'>{detail}</p>
      </CardContent>
    </Card>
  )
}

export default OverviewPage
