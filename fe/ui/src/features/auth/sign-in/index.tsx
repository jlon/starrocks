import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { UserAuthForm } from './components/user-auth-form'

export function SignIn({ redirectTo }: { redirectTo?: string }) {
  return (
    <div className='flex min-h-svh items-center justify-center p-4'>
      <Card className='w-full max-w-sm'>
        <CardHeader>
          <CardTitle className='text-lg tracking-tight'>
            StarRocks Web Console
          </CardTitle>
          <CardDescription>Sign in to continue.</CardDescription>
        </CardHeader>
        <CardContent>
          <UserAuthForm redirectTo={redirectTo} />
        </CardContent>
      </Card>
    </div>
  )
}
