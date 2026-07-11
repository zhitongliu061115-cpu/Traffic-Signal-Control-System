import type { AuthResult } from '@/api/auth'

const AUTH_TOKEN_KEY = 'traffic-auth-token'
const AUTH_USER_KEY = 'traffic-auth-user'

export function saveAuthSession(result: AuthResult): void {
  localStorage.setItem(AUTH_TOKEN_KEY, result.token)
  localStorage.setItem(AUTH_USER_KEY, JSON.stringify(result.user))
}

export function clearAuthSession(): void {
  localStorage.removeItem(AUTH_TOKEN_KEY)
  localStorage.removeItem(AUTH_USER_KEY)
}
