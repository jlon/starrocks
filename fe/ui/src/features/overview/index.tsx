import type { ReactNode } from 'react'
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
import { apiFetch } from '@/lib/api'
import type { Overview } from '@/lib/types'

function OverviewPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['overview'],
    queryFn: () => apiFetch<Overview>('/api/web/v1/overview'),
    refetchInterval: 5000,
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Overview'
        description='Cluster status and workload at a glance.'
        actions={
          data && (
            <Badge variant={data.fe.leader ? 'default' : 'secondary'}>
              {data.fe.type}
            </Badge>
          )
        }
      />

      {error && (
        <p className='text-destructive mb-4 text-sm'>{error.message}</p>
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
              detail={data.fe.leader ? 'Leader is serving metadata' : 'Follower'}
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

          <Card className='mt-4'>
            <CardHeader>
              <CardTitle>Version</CardTitle>
              <CardDescription>
                Build information reported by the frontend.
              </CardDescription>
            </CardHeader>
            <CardContent className='text-muted-foreground grid gap-1 text-sm'>
              <p>
                <span className='text-foreground font-medium'>Version:</span>{' '}
                {data.version.version}
              </p>
              <p>
                <span className='text-foreground font-medium'>Commit:</span>{' '}
                <code className='text-xs'>{data.version.commit}</code>
              </p>
              <p>
                <span className='text-foreground font-medium'>Build time:</span>{' '}
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
        <p className='text-muted-foreground text-xs'>{detail}</p>
      </CardContent>
    </Card>
  )
}

export default OverviewPage
