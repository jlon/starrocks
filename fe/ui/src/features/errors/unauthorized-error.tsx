import { ErrorPage } from '@/components/error-page'

export function UnauthorisedError() {
  return (
    <ErrorPage
      code='401'
      title='Unauthorized access'
      description='Please sign in with an account that has access to this resource.'
    />
  )
}
