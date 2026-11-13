import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from 'vitest-browser-react'
import { userEvent } from 'vitest/browser'
import { UserAuthForm } from './user-auth-form'

const { navigate, apiFetch } = vi.hoisted(() => ({
  navigate: vi.fn(),
  apiFetch: vi.fn(),
}))

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, apiFetch }
})

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return { ...actual, useNavigate: () => navigate }
})

function renderForm(redirectTo?: string) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <UserAuthForm redirectTo={redirectTo} />
    </QueryClientProvider>
  )
}

describe('UserAuthForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiFetch.mockResolvedValue({})
  })

  it('renders username, password and submit button', async () => {
    const screen = await renderForm()
    await expect
      .element(screen.getByRole('textbox', { name: /^Username$/i }))
      .toBeInTheDocument()
    await expect
      .element(screen.getByLabelText(/^Password$/i))
      .toBeInTheDocument()
    await expect
      .element(screen.getByRole('button', { name: /^Sign in$/i }))
      .toBeInTheDocument()
  })

  it('shows validation messages for an empty form', async () => {
    const screen = await renderForm()
    await userEvent.click(screen.getByRole('button', { name: /^Sign in$/i }))
    await expect
      .element(screen.getByText('Please enter your username.'))
      .toBeInTheDocument()
    await expect
      .element(screen.getByText('Please enter your password.'))
      .toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('posts credentials and navigates to the console root', async () => {
    const screen = await renderForm()
    await userEvent.fill(
      screen.getByRole('textbox', { name: /^Username$/i }),
      'admin'
    )
    await userEvent.fill(screen.getByLabelText(/^Password$/i), 'secret')
    await userEvent.click(screen.getByRole('button', { name: /^Sign in$/i }))

    await vi.waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith('/api/web/v1/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'admin', password: 'secret' }),
      })
    )
    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({ to: '/', replace: true })
    )
  })

  it('preserves the redirect query string', async () => {
    const screen = await renderForm('/ui/backends?tab=finished')
    await userEvent.fill(
      screen.getByRole('textbox', { name: /^Username$/i }),
      'admin'
    )
    await userEvent.fill(screen.getByLabelText(/^Password$/i), 'secret')
    await userEvent.click(screen.getByRole('button', { name: /^Sign in$/i }))

    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({
        to: '/backends',
        search: { tab: 'finished' },
        replace: true,
      })
    )
  })

  it('navigates to a redirect inside the console base path', async () => {
    const screen = await renderForm('/backends')
    await userEvent.fill(
      screen.getByRole('textbox', { name: /^Username$/i }),
      'admin'
    )
    await userEvent.fill(screen.getByLabelText(/^Password$/i), 'secret')
    await userEvent.click(screen.getByRole('button', { name: /^Sign in$/i }))

    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({ to: '/backends', replace: true })
    )
  })
})
