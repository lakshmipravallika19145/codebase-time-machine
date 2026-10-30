import { useEffect, useRef, useState } from 'react'
import type { ChatMessage, Mode } from '../types'
import { Answer } from '../Answer'

interface Props {
  repo: string
  mode: Mode
  recent: boolean
  messages: ChatMessage[]
  asking: boolean
  onMode: (m: Mode) => void
  onRecent: (v: boolean) => void
  onSend: (question: string) => void
  onCode: (file: string, start: number, end: number) => void
  onCommit: (hash: string) => void
}

const EXAMPLES: Record<Mode, string[]> = {
  full: [
    'Where is the main entry point of the application?',
    'Where is data saved to the database, and why was it written this way?',
  ],
  history: ['What changed most recently, and why?', 'Which commits touched the build configuration?'],
}

export function Chat(p: Props) {
  const [input, setInput] = useState('')
  const endRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [p.messages, p.asking])

  function submit(question: string) {
    const q = question.trim()
    if (!q || !p.repo || p.asking) return
    setInput('')
    p.onSend(q)
  }

  return (
    <div className="card chat">
      <div className="modes">
        <div className="segmented" role="tablist">
          <button type="button" id="mode-full" className={p.mode === 'full' ? 'on' : ''} onClick={() => p.onMode('full')}>
            Code + why
          </button>
          <button type="button" id="mode-history" className={p.mode === 'history' ? 'on' : ''} onClick={() => p.onMode('history')}>
            History
          </button>
        </div>
        {p.mode === 'history' && (
          <label className="toggle muted">
            <input type="checkbox" checked={p.recent} onChange={e => p.onRecent(e.target.checked)} /> Latest commits only
          </label>
        )}
      </div>

      <div className="messages">
        {p.messages.length === 0 && (
          <div className="examples">
            <h3>{p.mode === 'full' ? 'Where does it live, and why?' : 'Travel through the history'}</h3>
            <p className="muted">{p.repo ? 'Try one of these to get started:' : 'Index or select a repository to begin.'}</p>
            {EXAMPLES[p.mode].map(q => (
              <button key={q} className="example" onClick={() => submit(q)} disabled={!p.repo || p.asking}>
                {q}
              </button>
            ))}
          </div>
        )}
        {p.messages.map(m => (
          <div key={m.id} className={`msg ${m.role}${m.error ? ' error' : ''}`}>
            {m.role === 'assistant' && !m.error && m.mono ? (
              <pre className="msgbox">{m.text}</pre>
            ) : m.role === 'assistant' && !m.error ? (
              <Answer text={m.text} onCode={p.onCode} onCommit={p.onCommit} />
            ) : (
              <div className="plain">{m.text}</div>
            )}
            {m.role === 'assistant' && (m.coverage || (m.verified ?? 0) > 0 || (m.rejected ?? 0) > 0) && (
              <div className="badges">
                {((m.verified ?? 0) > 0 || (m.rejected ?? 0) > 0) && (
                  <span className="badge ok">
                    ✓ {m.verified ?? 0} citation{m.verified === 1 ? '' : 's'} verified
                  </span>
                )}
                {(m.rejected ?? 0) > 0 && <span className="badge warn">{m.rejected} unverifiable removed</span>}
                {m.coverage && <span className="badge">{m.coverage}</span>}
              </div>
            )}
          </div>
        ))}
        {p.asking && (
          <div className="msg assistant">
            <span className="typing">
              <i />
              <i />
              <i /> Searching and thinking… architecture and flow questions use several steps and can take 1-2 minutes.
            </span>
          </div>
        )}
        <div ref={endRef} />
      </div>

      <form
        className="row composer"
        onSubmit={e => {
          e.preventDefault()
          submit(input)
        }}
      >
        <input
          id="question-input"
          placeholder={p.repo ? 'Ask about the code or its history…' : 'Index or select a repository first'}
          value={input}
          onChange={e => setInput(e.target.value)}
          maxLength={500}
          disabled={!p.repo}
        />
        <button id="ask-button" type="submit" disabled={!p.repo || p.asking || !input.trim()}>
          Ask
        </button>
      </form>
    </div>
  )
}