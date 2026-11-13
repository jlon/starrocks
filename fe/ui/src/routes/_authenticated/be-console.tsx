import { createFileRoute } from '@tanstack/react-router'
import BeConsolePage from '@/features/be-console'

export const Route = createFileRoute('/_authenticated/be-console')({
  component: BeConsolePage,
})
