import { useQuery } from '@tanstack/react-query'
import { FileText } from 'lucide-react'
import { useState } from 'react'
import { PageHeader } from '@/components/page-header'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { apiFetch } from '@/lib/api'
import { formatBytes } from '@/lib/format'
import type { Backend, ProcProfileFile, ProcProfileList } from '@/lib/types'

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
  const [node, setNode] = useState('FE')
  const backends = useQuery({
    queryKey: ['backends'],
    queryFn: () => apiFetch<Backend[]>('/api/web/v1/backends'),
  })
  const profiles = useQuery({
    queryKey: ['proc-profiles', node],
    queryFn: () =>
      apiFetch<ProcProfileList>(
        `/api/web/v1/proc-profiles?node=${encodeURIComponent(node)}`
      ),
  })

  const beNodes = (backends.data ?? []).filter((backend) => backend.alive)

  return (
    <div className='space-y-4'>
      <PageHeader
        title='Proc Profiles'
        description='CPU and memory profiles captured on the frontend and backends.'
      />

      <div className='flex flex-wrap gap-2'>
        {['FE', ...beNodes.map((backend) => `BE:${backend.host}:${backend.httpPort}`)].map(
          (candidate) => (
            <Button
              key={candidate}
              variant={candidate === node ? 'default' : 'outline'}
              size='sm'
              onClick={() => setNode(candidate)}
            >
              {candidate === 'FE'
                ? 'FE'
                : `BE: ${candidate.slice(3)}`}
            </Button>
          )
        )}
      </div>

      {profiles.error && (
        <p className='text-destructive text-sm'>{profiles.error.message}</p>
      )}

      <div className='rounded-md border'>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Type</TableHead>
              <TableHead>Timestamp</TableHead>
              <TableHead>Size</TableHead>
              <TableHead className='text-right'>View</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {profiles.data?.files.map((file: ProcProfileFile) => (
              <TableRow key={file.filename}>
                <TableCell>{file.type}</TableCell>
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
            {profiles.data?.files.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className='text-muted-foreground h-24 text-center'>
                  No profile files found.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}
