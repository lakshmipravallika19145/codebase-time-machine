import { useEffect, useState } from 'react'
import { getCommit } from '../api'
import type { CommitDetails } from '../types'

interface Props {
  repo: string
  hash: string
}

export function CommitPanel({ repo, hash }: Props) {
  const [commit, setCommit] = useState<CommitDetails | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setCommit(null)
    setError(null)
    getCommit(repo, hash)
      .then(c => {
        if (!cancelled) setCommit(c)
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load the commit')
      })
    return () => {
      cancelled = true
    }
  }, [repo, hash])

  return (
    <div className="card">
      <h2>Commit {hash.slice(0, 7)}</h2>
      {error && <p className="error">{error}</p>}
      {!commit && !error && <p className="muted">Loading...</p>}
      {commit && (
        <>
          <p className="muted">
            {commit.author ?? 'unknown author'}, {new Date(commit.committed_at).toLocaleString()}
            {commit.additions !== null && ` | +${commit.additions} -${commit.deletions}`}
          </p>
          <h3>Commit message</h3>
          <pre className="msgbox">{commit.message}</pre>
          <p className="muted">This message is all the explanation Git recorded for this change.</p>
          {commit.files && (
            <>
              <h3>Files changed</h3>
              <pre className="msgbox">{commit.files}</pre>
            </>
          )}
        </>
      )}
    </div>
  )
}