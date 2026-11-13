import { createFileRoute } from '@tanstack/react-router'
import VariablesPage from '@/features/variables'

export const Route = createFileRoute('/_authenticated/variables')({
  component: VariablesPage,
})
