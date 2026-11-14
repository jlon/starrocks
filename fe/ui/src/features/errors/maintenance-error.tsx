import { ErrorPage } from '@/components/error-page'
import { Button } from '@/components/ui/button'

export function MaintenanceError() {
  return (
    <ErrorPage
      code='503'
      title='Under maintenance'
      description='The console is temporarily unavailable. We will be back shortly.'
      actions={<Button variant='outline'>Learn more</Button>}
    />
  )
}
