import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { AppMark } from '@/components/app-mark'
import { UserAuthForm } from './components/user-auth-form'

export function SignIn({ redirectTo }: { redirectTo?: string }) {
  return (
    <div className='flex min-h-svh items-center justify-center bg-muted/40 p-4'>
      <Card className='wc-enter w-full max-w-sm shadow-lg'>
        <CardHeader className='items-center text-center'>
          <AppMark className='size-12' iconClassName='size-6' />
          <CardTitle className='mt-2 text-lg tracking-tight'>
            StarRocks Web Console
          </CardTitle>
          <CardDescription>Sign in with a StarRocks account</CardDescription>
        </CardHeader>
        <CardContent>
          <UserAuthForm redirectTo={redirectTo} />
        </CardContent>
      </Card>
    </div>
  )
}
