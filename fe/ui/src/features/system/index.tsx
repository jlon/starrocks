import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowUp, Folder, RefreshCw } from 'lucide-react'
import { PageHeader } from '@/components/page-header'
import { Button } from '@/components/ui/button'
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
import type { SystemInfo } from '@/lib/types'

function SystemPage() {
  const [path, setPath] = useState('/')
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['system', path],
    queryFn: () =>
      apiFetch<SystemInfo>(
        `/api/web/v1/system?path=${encodeURIComponent(path)}`
      ),
  })

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='System'
        description='Browse the frontend proc tree, like /proc in Linux.'
        actions={
          <Button variant='outline' size='sm' onClick={() => refetch()}>
            <RefreshCw /> Refresh
          </Button>
        }
      />

      <div className='mb-4 flex flex-wrap items-center gap-2'>
        <Button
          variant='outline'
          size='icon'
          className='size-8'
          disabled={path === '/'}
          onClick={() => data && setPath(data.parent)}
        >
          <ArrowUp />
        </Button>
        <code className='bg-muted rounded-md px-2 py-1 text-xs'>{path}</code>
      </div>

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
                {(data?.columns ?? []).map((column) => (
                  <TableHead key={column}>{column}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.rows ?? []).map((row, rowIndex) => (
                <TableRow key={rowIndex}>
                  {row.map((value, columnIndex) => (
                    <TableCell
                      key={columnIndex}
                      className='max-w-[520px] align-top text-xs whitespace-pre-wrap'
                    >
                      {data?.isDir && columnIndex === 0 ? (
                        <Button
                          variant='link'
                          size='sm'
                          className='h-auto justify-start p-0 text-xs'
                          onClick={() =>
                            setPath(
                              path === '/' ? `/${value}` : `${path}/${value}`
                            )
                          }
                        >
                          <Folder /> {value}
                        </Button>
                      ) : isUrl(value) ? (
                        <a
                          href={value}
                          target='_blank'
                          rel='noreferrer'
                          className='text-primary underline'
                        >
                          {value}
                        </a>
                      ) : (
                        value
                      )}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
              {(data?.rows ?? []).length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={data?.columns.length ?? 1}
                    className='text-muted-foreground h-24 text-center'
                  >
                    Empty directory.
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

function isUrl(value: string | null): value is string {
  return value !== null && /^https?:\/\//.test(value)
}

export default SystemPage
