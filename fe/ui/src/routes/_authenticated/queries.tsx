import { createFileRoute } from '@tanstack/react-router'
import QueriesPage from '@/features/queries'

export const Route = createFileRoute('/_authenticated/queries')({
  component: QueriesPage,
})
