/* eslint-disable react-refresh/only-export-components */
import { z } from 'zod'
import { createFileRoute, redirect } from '@tanstack/react-router'
import { ApiError, apiFetch } from '@/lib/api'
import { SignIn } from '@/features/auth/sign-in'

const searchSchema = z.object({
  redirect: z.string().optional(),
})

const SignInRoute = () => {
  const { redirect: redirectTo } = Route.useSearch()
  return <SignIn redirectTo={redirectTo} />
}

export const Route = createFileRoute('/(auth)/sign-in')({
  validateSearch: searchSchema,
  beforeLoad: async () => {
    try {
      await apiFetch('/api/web/v1/session')
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return
      throw e
    }
    throw redirect({ to: '/' })
  },
  component: SignInRoute,
})
