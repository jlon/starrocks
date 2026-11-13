import { useState } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Loader2, LogIn } from 'lucide-react'
import { toast } from 'sonner'
import { ApiError, apiFetch } from '@/lib/api'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { PasswordInput } from '@/components/password-input'

const formSchema = z.object({
  username: z.string().min(1, 'Please enter your username.'),
  password: z.string().min(1, 'Please enter your password.'),
})

interface UserAuthFormProps extends React.HTMLAttributes<HTMLFormElement> {
  redirectTo?: string
}

export function UserAuthForm({
  className,
  redirectTo,
  ...props
}: UserAuthFormProps) {
  const [isLoading, setIsLoading] = useState(false)
  const navigate = useNavigate() as unknown as (options: {
    to: string
    search?: Record<string, string>
    replace?: boolean
  }) => void
  const queryClient = useQueryClient()
  const form = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: { username: '', password: '' },
  })

  async function onSubmit(data: z.infer<typeof formSchema>) {
    setIsLoading(true)
    try {
      await apiFetch('/api/web/v1/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      })
      queryClient.clear()
      toast.success('Signed in')
      const target = redirectTo
        ? new URL(redirectTo, window.location.origin)
        : null
      const base = import.meta.env.BASE_URL.replace(/\/$/, '')
      let path = target ? target.pathname : '/'
      for (const prefix of [base, '/ui']) {
        if (prefix && (path === prefix || path.startsWith(`${prefix}/`))) {
          path = path.slice(prefix.length) || '/'
          break
        }
      }
      const search = target
        ? Object.fromEntries(target.searchParams)
        : undefined
      if (search && Object.keys(search).length > 0) {
        navigate({ to: path, search, replace: true })
      } else {
        navigate({ to: path, replace: true })
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        toast.error('Invalid username or password')
      } else if (e instanceof ApiError && e.status === 403) {
        toast.error('This user does not have NODE privilege')
      } else {
        toast.error(e instanceof Error ? e.message : 'Sign in failed')
      }
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <Form {...form}>
      <form
        onSubmit={form.handleSubmit(onSubmit)}
        className={cn('grid gap-3', className)}
        {...props}
      >
        <FormField
          control={form.control}
          name='username'
          render={({ field }) => (
            <FormItem>
              <FormLabel>Username</FormLabel>
              <FormControl>
                <Input autoComplete='username' placeholder='admin' {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name='password'
          render={({ field }) => (
            <FormItem>
              <FormLabel>Password</FormLabel>
              <FormControl>
                <PasswordInput
                  autoComplete='current-password'
                  placeholder='********'
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <Button className='mt-2' disabled={isLoading}>
          {isLoading ? <Loader2 className='animate-spin' /> : <LogIn />}
          Sign in
        </Button>
      </form>
    </Form>
  )
}
