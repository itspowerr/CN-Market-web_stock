import {
  AuthStep1Response,
  AuthStep2Response,
  AuthStatusResponse,
} from '../types'
import { post, get } from './client'

export function authStep1(apiKey: string) {
  return post<AuthStep1Response>('/api/v1/auth/step1', { key: apiKey })
}

export function authStep2(uuid: string, code: string) {
  return post<AuthStep2Response>('/api/v1/auth/step2', { uuid, code })
}

export function authStatus() {
  return get<AuthStatusResponse>('/api/v1/auth/status')
}

export function authLogout() {
  return post<{ success: boolean }>('/api/v1/auth/logout', {})
}
