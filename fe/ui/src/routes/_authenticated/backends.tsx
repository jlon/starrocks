import { z } from 'zod'
import { createFileRoute } from '@tanstack/react-router'
import BackendsPage from '@/features/backends'

const searchSchema = z.object({
  filter: z.string().optional(),
  page: z.coerce.number().int().min(0).optional(),
})

export const Route = createFileRoute('/_authenticated/backends')({
  validateSearch: searchSchema,
  component: BackendsPage,
})
