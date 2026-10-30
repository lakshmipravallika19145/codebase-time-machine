import { useCallback, useEffect, useState } from 'react'
import { ask, listRepos } from './api'
import { Chat } from './components/Chat'
import { CodeViewer } from './components/CodeViewer'
import { CommitPanel } from './components/CommitPanel'
import { RepoPanel } from './components/RepoPanel'
import type { ChatMessage, Detail, Mode, RepoInfo } from './types'
import './App.css'

let nextId = 1

export default function App() {
  const [repos, setRepos] = useState<RepoInfo[]>([])
  const [repo, setRepo] = useState('')
  const [mode, setMode] = useState<Mode>('full')
  const [recent, setRecent] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [asking, setAsking] = useState(false)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const refreshRepos = useCallback(async (select?: string) => {
    try {
      const list = await listRepos()
      setRepos(list)
      setLoadError(null)
      setRepo(current => select ?? (current || list[0]?.repo || ''))
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Could not load repositories')
    }
  }, [])

  useEffect(() => {
    void refreshRepos()
  }, [refreshRepos])

  function selectRepo(name: string) {
    setRepo(name)
    setMessages([])
    setDetail(null)
  }

  async function send(question: string) {
    if (!repo || asking) return
    const userId = nextId++
    const replyId = nextId++
    setMessages(m => [...m, { id: userId, role: 'user', text: question }])
    setAsking(true)
    try {
      const r = await ask(mode, repo, question, recent)
      const commits = r.commitCitations ?? r.citations ?? []
      const verified = (r.codeCitations?.length ?? 0) + commits.length
      setMessages(m => [
        ...m,
                {
                  id: replyId,
                  role: 'assistant',
                  text: r.answer,
                  verified,
                  rejected: r.rejectedCitations,
                  coverage: r.coverage,
                  mono: r.kind === 'project-map',
                },
      ])
    } catch (e) {
      setMessages(m => [
        ...m,
        { id: replyId, role: 'assistant', text: e instanceof Error ? e.message : 'Something went wrong.', error: true },
      ])
    } finally {
      setAsking(false)
    }
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <div className="logo" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
              <path d="M3 3v5h5" />
              <path d="M12 7v5l3 3" />
            </svg>
          </div>
          <div>
            <h1>Codebase Time Machine</h1>
            <p className="muted">Ask where code lives and why it changed. Every citation is checked against the real code and history.</p>
          </div>
        </div>
      </header>
      <main>
        <section className="left">
          <RepoPanel
            repos={repos}
            selected={repo}
            loadError={loadError}
            onSelect={selectRepo}
            onIndexed={name => refreshRepos(name)}
          />
          <Chat
            repo={repo}
            mode={mode}
            recent={recent}
            messages={messages}
            asking={asking}
            onMode={setMode}
            onRecent={setRecent}
            onSend={send}
            onCode={(file, start, end) => setDetail({ kind: 'code', file, start, end })}
            onCommit={hash => setDetail({ kind: 'commit', hash })}
          />
        </section>
        <section className="right">
          {detail?.kind === 'code' && repo && (
            <CodeViewer repo={repo} file={detail.file} start={detail.start} end={detail.end} />
          )}
          {detail?.kind === 'commit' && repo && <CommitPanel repo={repo} hash={detail.hash} />}
          {!detail && (
            <div className="card placeholder">
              <div>
                <h3>Inspect a citation</h3>
                <p className="muted">Click a code or commit chip in an answer to see the highlighted lines or the full commit here.</p>
              </div>
            </div>
          )}
        </section>
      </main>
    </div>
  )
}