import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { Backend, BeConfig, BeTable, BeText } from '@/lib/types'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
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

type Tab = 'configs' | 'memory' | 'mem-tracker' | 'logs'

type BeData = { configs: BeConfig[]; table: BeTable | null; text: string }

const tabs: { value: Tab; label: string; page: string }[] = [
  { value: 'configs', label: 'Configs', page: '/api/web/v1/be/varz' },
  { value: 'memory', label: 'Memory', page: '/api/web/v1/be/memz' },
  {
    value: 'mem-tracker',
    label: 'Mem Tracker',
    page: '/api/web/v1/be/mem-tracker',
  },
  { value: 'logs', label: 'Logs', page: '/api/web/v1/be/logs' },
]

function BeConsolePage() {
  const [node, setNode] = useState('')
  const [tab, setTab] = useState<Tab>('configs')
  const [level, setLevel] = useState('2')
  const [filter, setFilter] = useState('')
  const [auto, setAuto] = useState(false)

  const { data: backends, isLoading: loadingBackends } = useQuery({
    queryKey: ['backends'],
    queryFn: () => apiFetch<{ backends: Backend[] }>('/api/web/v1/backends'),
  })

  const nodes = useMemo(
    () =>
      (backends?.backends ?? [])
        .filter((backend) => backend.alive)
        .map((backend) => ({
          value: `BE:${backend.host}:${backend.httpPort}`,
          label: `${backend.host}:${backend.httpPort}`,
        })),
    [backends]
  )

  const selectedNode = node || nodes[0]?.value || ''
  const active = tabs.find((item) => item.value === tab) ?? tabs[0]

  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['be', tab, selectedNode, level, active.page],
    enabled: !!selectedNode,
    refetchInterval: auto ? 10000 : false,
    queryFn: async (): Promise<BeData> => {
      const query = new URLSearchParams({ node: selectedNode })
      if (tab === 'mem-tracker') {
        query.set('upper_level', level)
      }
      const result = await apiFetch<
        { configs?: BeConfig[] } & Partial<BeTable> & Partial<BeText>
      >(`${active.page}?${query}`)
      return {
        configs: result.configs ?? [],
        table:
          result.headers && result.rows
            ? {
                node: result.node ?? selectedNode,
                headers: result.headers,
                rows: result.rows,
              }
            : null,
        text: result.text ?? '',
      }
    },
  })

  const configs = (data?.configs ?? []).filter((config) => {
    const keyword = filter.trim().toLowerCase()
    return (
      !keyword ||
      config.name.toLowerCase().includes(keyword) ||
      config.value.toLowerCase().includes(keyword)
    )
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='BE Console'
        description='Configuration, memory, tracker and log diagnostics from a backend.'
        actions={
          <AutoRefresh
            enabled={auto}
            onChange={setAuto}
            onRefresh={() => void refetch()}
            pending={isFetching}
          />
        }
      />

      <div className='mb-4 flex flex-wrap items-center gap-3'>
        <Select value={selectedNode} onValueChange={setNode}>
          <SelectTrigger className='w-[260px]'>
            <SelectValue placeholder='Select backend' />
          </SelectTrigger>
          <SelectContent>
            {nodes.map((item) => (
              <SelectItem key={item.value} value={item.value}>
                {item.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {tab === 'configs' && (
          <Input
            className='w-[240px]'
            placeholder='Filter configs…'
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          />
        )}

        {tab === 'mem-tracker' && (
          <Select value={level} onValueChange={setLevel}>
            <SelectTrigger className='w-[170px]'>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {['1', '2', '3', '4', '5'].map((value) => (
                <SelectItem key={value} value={value}>
                  {`Level ${value}`}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      {!loadingBackends && nodes.length === 0 && (
        <p className='text-sm text-muted-foreground'>No alive backend found.</p>
      )}
      {error && (
        <p className='mb-4 text-sm text-destructive'>{error.message}</p>
      )}

      <Tabs value={tab} onValueChange={(value) => setTab(value as Tab)}>
        <TabsList>
          {tabs.map((item) => (
            <TabsTrigger key={item.value} value={item.value}>
              {item.label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value='configs'>
          {isLoading ? (
            <Skeleton className='h-64 w-full rounded-xl' />
          ) : (
            <div className='max-h-[calc(100vh-280px)] overflow-auto rounded-xl border'>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Value</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {configs.map((config) => (
                    <TableRow key={config.name}>
                      <TableCell className='font-mono text-xs'>
                        {config.name}
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        {config.value}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </TabsContent>

        <TabsContent value='memory'>
          {isLoading ? (
            <Skeleton className='h-64 w-full rounded-xl' />
          ) : (
            <pre className='max-h-[calc(100vh-280px)] overflow-auto rounded-xl border p-4 text-xs whitespace-pre-wrap'>
              {data?.text || 'No data'}
            </pre>
          )}
        </TabsContent>

        <TabsContent value='mem-tracker'>
          {isLoading ? (
            <Skeleton className='h-64 w-full rounded-xl' />
          ) : (
            <div className='max-h-[calc(100vh-280px)] overflow-auto rounded-xl border'>
              <Table>
                <TableHeader>
                  <TableRow>
                    {(data?.table?.headers ?? []).map((header) => (
                      <TableHead key={header}>{header}</TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(data?.table?.rows ?? []).map((row, index) => (
                    <TableRow key={index}>
                      {row.map((cell, cellIndex) => (
                        <TableCell key={cellIndex} className='text-xs'>
                          {cell}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </TabsContent>

        <TabsContent value='logs'>
          {isLoading ? (
            <Skeleton className='h-64 w-full rounded-xl' />
          ) : (
            <pre className='max-h-[calc(100vh-280px)] overflow-auto rounded-xl border p-4 text-xs whitespace-pre-wrap'>
              {data?.text || 'No data'}
            </pre>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}

export default BeConsolePage
