import { StrictMode } from 'react'
import ReactDOM from 'react-dom/client'
import {
  QueryCache,
  QueryClient,
  QueryClientProvider,
} from '@tanstack/react-query'
import { RouterProvider, createRouter } from '@tanstack/react-router'
import { toast } from 'sonner'
import { handleServerError } from '@/lib/handle-server-error'
import { ThemeProvider } from './context/theme-provider'
import { routeTree } from './routeTree.gen'
import './styles/index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error instanceof Error && 'status' in error) {
          const s = (error as { status: number }).status
          if (s === 401 || s === 403) return false
        }
        return failureCount < 2
      },
      refetchOnWindowFocus: false,
      staleTime: 10 * 1000,
    },
    mutations: { onError: (e) => handleServerError(e) },
  },
  queryCache: new QueryCache({
    onError: (error) => {
      if (error instanceof Error && 'status' in error) {
        const s = (error as { status: number }).status
        if (s === 401) {
          toast.error('Session expired!')
          const redirect = window.location.href
          window.location.href = `/sign-in?redirect=${encodeURIComponent(redirect)}`
        }
      }
    },
  }),
})

const router = createRouter({
  routeTree,
  context: { queryClient },
  defaultPreload: 'intent',
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

const rootElement = document.getElementById('root')!
if (!rootElement.innerHTML) {
  const root = ReactDOM.createRoot(rootElement)
  root.render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider>
          <RouterProvider router={router} />
        </ThemeProvider>
      </QueryClientProvider>
    </StrictMode>
  )
}
