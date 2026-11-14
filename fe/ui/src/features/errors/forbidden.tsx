import { ErrorPage } from '@/components/error-page'

export function ForbiddenError() {
  return (
    <ErrorPage
      code='403'
      title='Access forbidden'
      description='You do not have permission to view this resource.'
    />
  )
}
