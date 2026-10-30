import { useEffect, useRef, useState } from 'react'
import { getFile } from '../api'

interface Props {
  repo: string
  file: string
  start: number
  end: number
}

export function CodeViewer({ repo, file, start, end }: Props) {
  const [lines, setLines] = useState<string[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const firstRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    let cancelled = false
    setLines(null)
    setError(null)
    getFile(repo, file)
      .then(text => {
        if (cancelled) return
        const parts = text.split(/\r\n|\n|\r/)
        if (parts.length > 0 && parts[parts.length - 1] === '') parts.pop()
        setLines(parts)
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load the file')
      })
    return () => {
      cancelled = true
    }
  }, [repo, file])

  useEffect(() => {
    firstRef.current?.scrollIntoView({ block: 'center' })
  }, [lines, start])

  return (
    <div className="viewer card">
      <div className="viewer-head">
        <strong>{file}</strong>
        <span className="range">lines {start}–{end}</span>
      </div>
      {error && <p className="error">{error}</p>}
      {!lines && !error && <p className="muted">Loading...</p>}
      {lines && (
        <div className="code">
          {lines.map((text, i) => {
            const n = i + 1
            const on = n >= start && n <= end
            return (
              <div key={n} ref={n === start ? firstRef : undefined} className={on ? 'line hl' : 'line'}>
                <span className="ln">{n}</span>
                <span className="tx">{text || ' '}</span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}