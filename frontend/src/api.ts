import type { AskResponse, CommitDetails, Mode, RepoInfo } from './types'

const BASE: string = import.meta.env.VITE_API_URL ?? 'http://localhost:8081'

export class ApiError extends Error {}

async function fetchOk(path: string, init?: RequestInit): Promise<Response> {
  let res: Response
  try {
    res = await fetch(BASE + path, init)
  } catch {
    throw new ApiError('Cannot reach the server. Is the backend running?')
  }
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try {
      const body = await res.json()
      if (body && typeof body.message === 'string') message = body.message
    } catch {
      // the error body was not JSON; keep the generic message
    }
    throw new ApiError(message)
  }
  return res
}

async function getJson<T>(path: string): Promise<T> {
  return (await fetchOk(path)).json() as Promise<T>
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetchOk(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json() as Promise<T>
}

const enc = encodeURIComponent

export const listRepos = () => getJson<RepoInfo[]>('/repos')

export const indexRepo = (repoUrl: string) =>
  postJson<{ repo: string; chunks: number }>('/index', { repoUrl })

export const indexHistory = (repo: string) =>
  postJson<{ commits: number }>('/index-history', { repo })

export const indexBlame = (repo: string) =>
  postJson<{ blameRows: number }>('/index-blame', { repo })

export const ask = (mode: Mode, repo: string, question: string, recent: boolean) =>
  mode === 'full'
    ? postJson<AskResponse>('/ask-full', { repo, question })
    : postJson<AskResponse>('/ask-history', { repo, question, mode: recent ? 'recent' : 'search' })

export async function getFile(repo: string, path: string): Promise<string> {
  return (await fetchOk(`/file?repo=${enc(repo)}&path=${enc(path)}`)).text()
}

export const getCommit = (repo: string, hash: string) =>
  getJson<CommitDetails>(`/commit?repo=${enc(repo)}&hash=${enc(hash)}`)