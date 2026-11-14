import { ErrorPage } from '@/components/error-page'

type GeneralErrorProps = {
  minimal?: boolean
}

export function GeneralError({ minimal = false }: GeneralErrorProps) {
  return (
    <ErrorPage
      code='500'
      title='Something went wrong'
      description={
        minimal
          ? 'Please try again later.'
          : 'We apologize for the inconvenience. Please try again later.'
      }
    />
  )
}
