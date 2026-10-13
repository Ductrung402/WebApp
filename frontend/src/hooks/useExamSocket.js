import { useEffect, useRef, useState } from 'react'
import { connectExamRoom } from '../services/socket.js'

export default function useExamSocket(membership, onStudents, onClosed) {
  const identity = `${membership?.maCodePhongThi || ''}:${membership?.userType || ''}:${membership?.idHs || ''}`
  const initial = (key) => ({ key, status: 'idle', error: '', exam: null, clock: null, submission: null, studentState: null, closed: false, recoveryReady: false, syncReady: false })
  const [state, setState] = useState(() => initial(identity))
  const [version, setVersion] = useState(0)
  const listener = useRef(onStudents)
  listener.current = onStudents
  const closedListener = useRef(onClosed)
  closedListener.current = onClosed
  const code = membership?.maCodePhongThi
  const userType = membership?.userType
  const studentId = membership?.idHs
  useEffect(() => {
    if (!code) { setState(initial(identity)); return }
    return connectExamRoom({ maCodePhongThi: code, userType, idHs: studentId }, {
      onStatus: (status, error = '') => setState((current) => ({ ...(current.key === identity ? current : initial(identity)), status, error, ...(['connecting', 'joining', 'recovering', 'disconnected'].includes(status) ? { recoveryReady: false } : {}) })),
      onStudents: (packet) => listener.current?.(packet),
      onClosed: (packet) => closedListener.current?.(packet),
      onSnapshot: (snapshot) => setState((current) => ({ ...current, ...snapshot, key: identity, status: snapshot.recoveryReady === false ? current.status : 'joined', syncReady: true, error: '' })),
    })
  }, [code, userType, studentId, version])
  const connection = { ...(state.key === identity ? state : initial(identity)) }
  // This internal identity is not a React key; never spread it into JSX props.
  delete connection.key
  return { ...connection, retry: () => setVersion((value) => value + 1) }
}
