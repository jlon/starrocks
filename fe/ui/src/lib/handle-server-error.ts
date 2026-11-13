import { toast } from 'sonner'

export function handleServerError(error: unknown) {
  if (import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.log(error)
  }

  let errMsg = 'Something went wrong!'

  if (
    error &&
    typeof error === 'object' &&
    'status' in error &&
    Number(error.status) === 204
  ) {
    errMsg = 'No content.'
  }

  if (error && typeof error === 'object' && 'message' in error) {
    const m = (error as { message?: string }).message
    if (typeof m === 'string' && m.length > 0) errMsg = m
  }

  toast.error(errMsg)
}
