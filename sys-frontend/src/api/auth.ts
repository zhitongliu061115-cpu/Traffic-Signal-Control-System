const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

export interface AuthUser {
  email: string
  id: string
  username: string
}

export interface AuthResult {
  token: string
  user: AuthUser
}

export interface LoginPayload {
  email: string
  password: string
  username: string
}

export interface CaptchaLoginPayload {
  captcha: string
  email: string
}

export interface RegisterPayload extends LoginPayload {
  inviteCode: string
}

async function postAuth<T>(path: string, payload: unknown): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    body: JSON.stringify(payload),
    headers: {
      'Content-Type': 'application/json',
    },
    method: 'POST',
  })

  const body = (await response.json().catch(() => null)) as ApiResponse<T> | null
  if (!body || !body.success) {
    throw new Error(body?.message || `auth request failed: ${response.status}`)
  }

  if (!response.ok) {
    throw new Error(body.message || `auth request failed: ${response.status}`)
  }

  return body.data
}

export function login(payload: LoginPayload): Promise<AuthResult> {
  return postAuth<AuthResult>('/api/auth/login', payload)
}

export function loginWithCaptcha(payload: CaptchaLoginPayload): Promise<AuthResult> {
  return postAuth<AuthResult>('/api/auth/captcha-login', payload)
}

export function register(payload: RegisterPayload): Promise<AuthResult> {
  return postAuth<AuthResult>('/api/auth/register', payload)
}

export function sendCaptcha(email: string): Promise<void> {
  return postAuth<void>('/api/auth/send-captcha', { email })
}
