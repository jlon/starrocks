import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { apiFetch } from '@/lib/api'
import type { VariableData } from '@/lib/types'
import { Input } from '@/components/ui/input'
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
import { PageHeader } from '@/components/page-header'

function VariablesPage() {
  const [search, setSearch] = useState('')
  const { data, isLoading, error } = useQuery({
    queryKey: ['variables'],
    queryFn: () => apiFetch<VariableData>('/api/web/v1/variables'),
  })

  const needle = search.trim().toLowerCase()
  const config = useMemo(
    () =>
      Object.entries(data?.config ?? {}).filter(
        ([key, value]) =>
          !needle ||
          key.toLowerCase().includes(needle) ||
          value.toLowerCase().includes(needle)
      ),
    [data, needle]
  )
  const variables = useMemo(
    () =>
      (data?.variables ?? []).filter(
        (variable) =>
          !needle ||
          variable.name.toLowerCase().includes(needle) ||
          variable.value.toLowerCase().includes(needle)
      ),
    [data, needle]
  )

  return (
    <div className='p-4 md:p-6'>
      <PageHeader
        title='Variables'
        description='Frontend configuration and global session variables.'
      />

      <div className='relative mb-4 max-w-sm'>
        <Search className='absolute top-2.5 left-2.5 size-4 text-muted-foreground' />
        <Input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder='Filter by name or value'
          className='pl-8'
        />
      </div>

      {error && (
        <p className='mb-4 text-sm text-destructive'>{error.message}</p>
      )}
      {data?.configError && (
        <p className='mb-4 text-sm text-destructive'>{data.configError}</p>
      )}

      {isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <Tabs defaultValue='config'>
          <TabsList>
            <TabsTrigger value='config'>Config ({config.length})</TabsTrigger>
            <TabsTrigger value='variables'>
              Variables ({variables.length})
            </TabsTrigger>
          </TabsList>

          <TabsContent value='config'>
            <div className='wc-fade max-h-[70vh] overflow-auto bg-card rounded-xl border'>
              <Table>
                <TableHeader className='sticky top-0 bg-card'>
                  <TableRow>
                    <TableHead className='w-1/3'>Name</TableHead>
                    <TableHead>Value</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {config.map(([key, value]) => (
                    <TableRow key={key}>
                      <TableCell className='font-mono text-xs'>
                        <Highlight text={key} needle={needle} />
                      </TableCell>
                      <TableCell className='font-mono text-xs break-all'>
                        <Highlight text={value} needle={needle} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </TabsContent>

          <TabsContent value='variables'>
            <div className='wc-fade max-h-[70vh] overflow-auto bg-card rounded-xl border'>
              <Table>
                <TableHeader className='sticky top-0 bg-card'>
                  <TableRow>
                    <TableHead className='w-1/3'>Name</TableHead>
                    <TableHead>Value</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {variables.map((variable) => (
                    <TableRow key={variable.name}>
                      <TableCell className='font-mono text-xs'>
                        <Highlight text={variable.name} needle={needle} />
                      </TableCell>
                      <TableCell className='font-mono text-xs break-all'>
                        <Highlight text={variable.value} needle={needle} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </TabsContent>
        </Tabs>
      )}
    </div>
  )
}

function Highlight({ text, needle }: { text: string; needle: string }) {
  if (!needle) return <>{text}</>
  const index = text.toLowerCase().indexOf(needle)
  if (index < 0) return <>{text}</>
  return (
    <>
      {text.slice(0, index)}
      <mark className='rounded-sm bg-amber-200/60 px-0.5 dark:bg-amber-400/25'>
        {text.slice(index, index + needle.length)}
      </mark>
      {text.slice(index + needle.length)}
    </>
  )
}

export default VariablesPage
