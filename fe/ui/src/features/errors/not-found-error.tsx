import { ErrorPage } from '@/components/error-page'

export function NotFoundError() {
  return (
    <ErrorPage
      code='404'
      title='Page not found'
      description='The page you are looking for does not exist or was removed.'
    />
  )
}
