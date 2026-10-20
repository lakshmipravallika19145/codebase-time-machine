import { useState } from 'react'
import { indexBlame, indexHistory, indexRepo } from '../api'
import type { RepoInfo } from '../types'

interface Props {
  repos: RepoInfo[]
  selected: string
  loadError: string | null
  onSelect: (repo: string) => void
  onIndexed: (repo: string) => Promise<void>
}

const STEPS = [
  'Downloading the repo and cutting code into chunks',
  'Reading the commit history',
  'Linking lines to commits (git blame)',
]

export function RepoPanel({ repos, selected, loadError, onSelect, onIndexed }: Props) {
  const [url, setUrl] = useState('')
  const [step, setStep] = useState(0) // 0 = idle, 1 to 3 = running that step
  const [error, setError] = useState<string | null>(null)
  const busy = step > 0
  const validUrl = /^https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/?$/.test(url.trim())

  async function run() {
    setError(null)
    try {
      setStep(1)
      const { repo } = await indexRepo(url.trim())
      setStep(2)
      await indexHistory(repo)
      setStep(3)
      await indexBlame(repo)
      await onIndexed(repo)
      setUrl('')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Indexing failed')
    } finally {
      setStep(0)
    }
  }

  return (
    <div className="card">
      <h2>Repository</h2>
      {repos.length > 0 ? (
        <select value={selected} onChange={e => onSelect(e.target.value)} disabled={busy}>
          {repos.map(r => (
            <option key={r.repo} value={r.repo}>
              {r.repo} ({r.chunks} chunks, {r.commits} commits)
            </option>
          ))}
        </select>
      ) : (
        <p className="muted">No repositories yet. Index one below.</p>
      )}
      {loadError && <p className="error">{loadError}</p>}

      <div className="row">
        <input
          type="url"
          placeholder="https://github.com/owner/repo"
          value={url}
          onChange={e => setUrl(e.target.value)}
          disabled={busy}
        />
        <button onClick={run} disabled={busy || !validUrl}>
          {busy ? 'Indexing...' : 'Index it'}
        </button>
      </div>

      {busy && (
        <ol className="steps">
          {STEPS.map((label, i) => (
            <li key={label} className={i + 1 < step ? 'done' : i + 1 === step ? 'active' : ''}>
              {label}
            </li>
          ))}
        </ol>
      )}
      {error && <p className="error">{error}</p>}
    </div>
  )
}