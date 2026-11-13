import { createFileRoute } from '@tanstack/react-router'
import HaPage from '@/features/ha'

export const Route = createFileRoute('/_authenticated/ha')({
  component: HaPage,
})
