export class ApiError extends Error {
  status: number
  code?: string
  constructor(status: number, message: string, code?: string) {
    super(message)
    this.status = status
    this.code = code
    this.name = 'ApiError'
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { credentials: 'same-origin', ...init })
  if (!res.ok) {
    let message = res.statusText
    let code: string | undefined
    try {
      const body = await res.json()
      if (body?.message) message = body.message
      if (body?.code) code = body.code
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, message, code)
  }
  const ct = res.headers.get('content-type') ?? ''
  if (ct.includes('application/json')) return (await res.json()) as T
  return (await res.text()) as unknown as T
}
