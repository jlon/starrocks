/* eslint-disable react-refresh/only-export-components */
import { createFileRoute } from '@tanstack/react-router'
import { QueryProfilePage } from '@/features/query-profile'

type ProfileSearch = {
  queryId?: string
}

const QueryProfileRoute = () => {
  const { queryId } = Route.useSearch()
  return <QueryProfilePage key={queryId ?? 'empty'} initialQueryId={queryId} />
}

export const Route = createFileRoute('/_authenticated/query-profile')({
  validateSearch: (search: Record<string, unknown>): ProfileSearch => ({
    queryId:
      typeof search.queryId === 'string' && search.queryId
        ? search.queryId
        : undefined,
  }),
  component: QueryProfileRoute,
})
