import { useEffect, useState } from "react";

import { formatDuration, listModes, ModeInfo } from "./ipc";

// Scaffold UI: reads the mode table from the codec through the Tauri bridge,
// which exercises the whole stack (React -> IPC -> Rust -> C). The RX, Gallery,
// TX, Waterfall, Log and Settings tabs land in the PRs that follow.
export default function App() {
  const [modes, setModes] = useState<ModeInfo[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listModes()
      .then(setModes)
      .catch((e) => setError(String(e)));
  }, []);

  return (
    <main>
      <header>
        <h1>SSTVAF</h1>
        <p className="sub">Slow-scan television — desktop</p>
      </header>

      {error && <p className="error">Codec unavailable: {error}</p>}

      <section>
        <h2>Modes</h2>
        <table>
          <thead>
            <tr>
              <th>Mode</th>
              <th>Size</th>
              <th>VIS</th>
              <th>TX time</th>
            </tr>
          </thead>
          <tbody>
            {modes.map((m) => (
              <tr key={m.id}>
                <td>
                  {m.name} <span className="dim">{m.short_code}</span>
                </td>
                <td>
                  {m.width}&times;{m.height}
                </td>
                <td>{m.vis_code}</td>
                <td>{formatDuration(m.tx_seconds)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </main>
  );
}
