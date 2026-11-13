import { createFileRoute } from '@tanstack/react-router'
import BackendsPage from '@/features/backends'

export const Route = createFileRoute('/_authenticated/backends')({
  component: BackendsPage,
})
