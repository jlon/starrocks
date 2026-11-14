import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { FileText } from 'lucide-react'
import { EmptyState } from '@/components/empty-state'
import { apiFetch } from '@/lib/api'
import { formatBytes } from '@/lib/format'
import type { Backend, ProcProfileFile, ProcProfileList } from '@/lib/types'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { StateChip } from '@/components/state-badge'
import { PageHeader } from '@/components/page-header'

function formatProfileTime(value: string): string {
  const match = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})$/.exec(value)
  if (!match) return value
  const [, year, month, day, hour, minute, second] = match
  return new Date(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second)
  ).toLocaleString()
}

function profileUrl(node: string, filename: string): string {
  return `/api/web/v1/proc-profiles/file?node=${encodeURIComponent(node)}&filename=${encodeURIComponent(filename)}`
}

export default function ProcProfilesPage() {
  const [mode, setMode] = useState<'FE' | 'BE'>('FE')
  const [backendNode, setBackendNode] = useState('')
  const backends = useQuery({
    queryKey: ['backends'],
    queryFn: () => apiFetch<{ backends: Backend[] }>('/api/web/v1/backends'),
  })
  const beNodes = (backends.data?.backends ?? []).filter(
    (backend) => backend.alive
  )
  const activeBackend = beNodes.find(
    (backend) => `BE:${backend.host}:${backend.httpPort}` === backendNode
  )
  const node =
    mode === 'FE'
      ? 'FE'
      : activeBackend
        ? `BE:${activeBackend.host}:${activeBackend.httpPort}`
        : ''
  const profiles = useQuery({
    queryKey: ['proc-profiles', node],
    queryFn: () =>
      apiFetch<ProcProfileList>(
        `/api/web/v1/proc-profiles?node=${encodeURIComponent(node)}`
      ),
    enabled: node.length > 0,
  })

  return (
    <div className='space-y-4 p-4 md:p-6'>
      <PageHeader
        title='Proc Profiles'
        description='CPU and memory profiles captured on the frontend and backends.'
      />

      <Tabs
        value={mode}
        onValueChange={(value) => {
          setMode(value as 'FE' | 'BE')
          if (value === 'BE' && !backendNode && beNodes[0]) {
            setBackendNode(`BE:${beNodes[0].host}:${beNodes[0].httpPort}`)
          }
        }}
      >
        <TabsList>
          <TabsTrigger value='FE'>Frontend</TabsTrigger>
          <TabsTrigger value='BE'>Backend</TabsTrigger>
        </TabsList>
        <TabsContent value='FE' className='mt-3'>
          <ProfileTable
            node='FE'
            profiles={profiles.data}
            error={profiles.error?.message}
          />
        </TabsContent>
        <TabsContent value='BE' className='mt-3 space-y-3'>
          <Select value={backendNode} onValueChange={setBackendNode}>
            <SelectTrigger className='w-80'>
              <SelectValue placeholder='Select a backend' />
            </SelectTrigger>
            <SelectContent>
              {beNodes.map((backend) => {
                const value = `BE:${backend.host}:${backend.httpPort}`
                return (
                  <SelectItem key={value} value={value}>
                    {backend.host}:{backend.httpPort}
                  </SelectItem>
                )
              })}
            </SelectContent>
          </Select>
          {!backendNode && (
            <p className='text-sm text-muted-foreground'>
              Select an alive backend to list its profiles.
            </p>
          )}
          {backendNode && (
            <ProfileTable
              node={backendNode}
              profiles={profiles.data}
              error={profiles.error?.message}
            />
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}

function ProfileTable({
  node,
  profiles,
  error,
}: {
  node: string
  profiles?: ProcProfileList
  error?: string
}) {
  return (
    <>
      {error && <p className='mb-2 text-sm text-destructive'>{error}</p>}
      <div className='wc-fade max-h-[calc(100vh-300px)] overflow-auto bg-card rounded-md border'>
        <Table>
          <TableHeader className='sticky top-0 z-10 bg-card'>
            <TableRow>
              <TableHead className='whitespace-nowrap'>Type</TableHead>
              <TableHead className='whitespace-nowrap'>Timestamp</TableHead>
              <TableHead className='whitespace-nowrap'>Size</TableHead>
              <TableHead className='text-right'>View</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(profiles?.files ?? []).map((file: ProcProfileFile) => (
              <TableRow key={file.filename}>
                <TableCell>
                  <StateChip tone={file.type === 'CPU' ? 'sky' : 'violet'}>
                    {file.type}
                  </StateChip>
                </TableCell>
                <TableCell>{formatProfileTime(file.timestamp)}</TableCell>
                <TableCell>{formatBytes(file.size)}</TableCell>
                <TableCell className='text-right'>
                  <Button variant='ghost' size='sm' asChild>
                    <a
                      href={profileUrl(node, file.filename)}
                      target='_blank'
                      rel='noreferrer'
                    >
                      <FileText className='size-4' />
                      View
                    </a>
                  </Button>
                </TableCell>
              </TableRow>
            ))}
            {(profiles?.files ?? []).length === 0 && (
              <TableRow>
                <TableCell colSpan={4}>
                  <EmptyState
                    icon={FileText}
                    title='No profile files'
                    description='No CPU or memory profiles are available on this node.'
                  />
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
    </>
  )
}
