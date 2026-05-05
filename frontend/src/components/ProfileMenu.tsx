import { useState } from 'react'

interface Props {
  displayName: string
  onLogout: () => void
}

export default function ProfileMenu({ displayName, onLogout }: Props) {
  const [open, setOpen] = useState(false)
  const initials = displayName.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase()

  return (
    <div style={styles.wrapper}>
      <button style={styles.avatar} onClick={() => setOpen(o => !o)}>
        {initials}
      </button>
      {open && (
        <div style={styles.menu}>
          <button style={styles.menuItem} onClick={() => { setOpen(false); onLogout() }}>
            ⎋ Logout
          </button>
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper:  { position: 'relative' },
  avatar:   { width: 32, height: 32, borderRadius: '50%', background: 'var(--bg-surface)', border: '1px solid var(--border-hover)', color: 'var(--text-secondary)', fontSize: 13, fontWeight: 600, display: 'flex', alignItems: 'center', justifyContent: 'center' },
  menu:     { position: 'absolute', top: 38, right: 0, background: 'var(--bg-surface)', border: '1px solid var(--border)', borderRadius: 8, padding: 6, minWidth: 120, zIndex: 100 },
  menuItem: { display: 'block', width: '100%', padding: '7px 12px', background: 'none', color: 'var(--error)', fontSize: 13, textAlign: 'left', borderRadius: 5 },
}
