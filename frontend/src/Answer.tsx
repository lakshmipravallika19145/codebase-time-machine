import { Fragment } from 'react'

const TAG = /(\[[^[\]\s:`*()"'<>]+\.[A-Za-z0-9]{1,6}:\d+-\d+\]|\[commit:[0-9a-fA-F]{7,40}\])/
const CODE = /^\[([^\]]+):(\d+)-(\d+)\]$/
const COMMIT = /^\[commit:([0-9a-fA-F]{7,40})\]$/

interface Props {
  text: string
  onCode: (file: string, start: number, end: number) => void
  onCommit: (hash: string) => void
}

function Plain({ text }: { text: string }) {
  return (
    <>
      {text.split(/(\*\*[^*\n]+\*\*)/).map((s, i) =>
        s.length > 4 && s.startsWith('**') && s.endsWith('**') ? (
          <strong key={i}>{s.slice(2, -2)}</strong>
        ) : (
          <Fragment key={i}>{s}</Fragment>
        ),
      )}
    </>
  )
}

export function Answer({ text, onCode, onCommit }: Props) {
  return (
    <div className="answer">
      {text.split(TAG).map((part, i) => {
        const commit = COMMIT.exec(part)
        if (commit) {
          return (
            <button key={i} className="chip chip-commit" title="Show commit" onClick={() => onCommit(commit[1])}>
              commit {commit[1].slice(0, 7)}
            </button>
          )
        }
        const code = CODE.exec(part)
        if (code) {
          const name = code[1].split('/').pop() ?? code[1]
          return (
            <button
              key={i}
              className="chip chip-code"
              title={code[1]}
              onClick={() => onCode(code[1], Number(code[2]), Number(code[3]))}
            >
              {name}:{code[2]}-{code[3]}
            </button>
          )
        }
        return <Plain key={i} text={part} />
      })}
    </div>
  )
}