import { createFileRoute } from '@tanstack/react-router'
import ProcProfilesPage from '@/features/proc-profiles'

export const Route = createFileRoute('/_authenticated/proc-profiles')({
  component: ProcProfilesPage,
})
