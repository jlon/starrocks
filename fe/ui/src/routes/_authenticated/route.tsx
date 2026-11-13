import { createFileRoute, redirect } from '@tanstack/react-router'
import { ApiError, apiFetch } from '@/lib/api'
import { AuthenticatedLayout } from '@/components/layout/authenticated-layout'

export const Route = createFileRoute('/_authenticated')({
  beforeLoad: async ({ location }) => {
    try {
      await apiFetch('/api/web/v1/session')
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        throw redirect({ to: '/sign-in', search: { redirect: location.href } })
      }
      throw e
    }
  },
  component: AuthenticatedLayout,
})
