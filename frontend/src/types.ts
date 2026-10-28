export interface RepoInfo {
  repo: string
  chunks: number
  commits: number
  blame: number
}

export interface CodeCitation {
  file: string
  startLine: number
  endLine: number
}

export interface CommitCitation {
  commit: string
  date: string
  author: string
  subject: string
}

// /ask-full returns codeCitations + commitCitations; /ask-history returns citations (commits).
export interface AskResponse {
  answer: string
  codeCitations?: CodeCitation[]
  commitCitations?: CommitCitation[]
  citations?: CommitCitation[]
  rejectedCitations: number
  sources: string[]
  coverage?: string
  kind?: string
}

export interface CommitDetails {
  hash: string
  author: string | null
  committed_at: string | number
  message: string
  files: string | null
  additions: number | null
  deletions: number | null
}

export type Mode = 'full' | 'history'

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  verified?: number
  rejected?: number
  error?: boolean
  coverage?: string
  mono?: boolean
}

export type Detail =
  | { kind: 'code'; file: string; start: number; end: number }
  | { kind: 'commit'; hash: string }