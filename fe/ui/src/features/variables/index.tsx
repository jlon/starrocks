import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { PageHeader } from '@/components/page-header'
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
import { apiFetch } from '@/lib/api'
import type { VariableData } from '@/lib/types'

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
        <Search className='text-muted-foreground absolute top-2.5 left-2.5 size-4' />
        <Input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder='Filter by name or value'
          className='pl-8'
        />
      </div>

      {error && (
        <p className='text-destructive mb-4 text-sm'>{error.message}</p>
      )}
      {data?.configError && (
        <p className='text-destructive mb-4 text-sm'>{data.configError}</p>
      )}

      {isLoading ? (
        <Skeleton className='h-64 w-full rounded-xl' />
      ) : (
        <Tabs defaultValue='config'>
          <TabsList>
            <TabsTrigger value='config'>
              Config ({config.length})
            </TabsTrigger>
            <TabsTrigger value='variables'>
              Variables ({variables.length})
            </TabsTrigger>
          </TabsList>

          <TabsContent value='config'>
            <div className='max-h-[70vh] overflow-auto rounded-xl border'>
              <Table>
                <TableHeader className='bg-background sticky top-0'>
                  <TableRow>
                    <TableHead className='w-1/3'>Name</TableHead>
                    <TableHead>Value</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {config.map(([key, value]) => (
                    <TableRow key={key}>
                      <TableCell className='font-mono text-xs'>{key}</TableCell>
                      <TableCell className='font-mono text-xs break-all'>
                        {value}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </TabsContent>

          <TabsContent value='variables'>
            <div className='max-h-[70vh] overflow-auto rounded-xl border'>
              <Table>
                <TableHeader className='bg-background sticky top-0'>
                  <TableRow>
                    <TableHead className='w-1/3'>Name</TableHead>
                    <TableHead>Value</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {variables.map((variable) => (
                    <TableRow key={variable.name}>
                      <TableCell className='font-mono text-xs'>
                        {variable.name}
                      </TableCell>
                      <TableCell className='font-mono text-xs break-all'>
                        {variable.value}
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

export default VariablesPage
