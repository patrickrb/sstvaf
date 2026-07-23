import { useCallback, useEffect, useRef, useState } from "react";

import {
  AudioDevice,
  describeDevice,
  listAudioInputs,
  onRxEvent,
  startRx,
  stopRx,
} from "./ipc";
import {
  argbToRgba,
  describeRxState,
  describeSlant,
  progressPercent,
  qualityPercent,
  stateMode,
  type RxEvent,
  type RxState,
} from "./rx";

const LOG_LIMIT = 50;

export default function App() {
  const [devices, setDevices] = useState<AudioDevice[]>([]);
  const [device, setDevice] = useState<string>("");
  const [running, setRunning] = useState(false);
  const [state, setState] = useState<RxState>({ kind: "idle" });
  const [log, setLog] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const canvasRef = useRef<HTMLCanvasElement>(null);
  // The geometry the canvas is currently sized for, so it is only resized when
  // the mode actually changes — resizing clears the image being painted.
  const canvasMode = useRef<string | null>(null);

  const appendLog = useCallback((line: string) => {
    setLog((prev) => [line, ...prev].slice(0, LOG_LIMIT));
  }, []);

  useEffect(() => {
    listAudioInputs()
      .then((d) => {
        setDevices(d);
        const def = d.find((x) => x.is_default);
        if (def) setDevice(def.id);
      })
      .catch((e) => setError(String(e)));
  }, []);

  // Paint incoming rows straight onto the canvas as they decode.
  const paintRows = useCallback((firstRow: number, width: number, pixels: number[]) => {
    const canvas = canvasRef.current;
    if (!canvas || width === 0) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const rows = Math.floor(pixels.length / width);
    if (rows < 1) return;
    const img = new ImageData(argbToRgba(pixels), width, rows);
    ctx.putImageData(img, 0, firstRow);
  }, []);

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    onRxEvent((e: RxEvent) => {
      switch (e.event) {
        case "state":
          setState(e.data);
          break;
        case "rows":
          paintRows(e.data.first_row, e.data.width, e.data.pixels);
          break;
        case "info":
          appendLog(e.data);
          break;
        case "error":
          appendLog(`⚠ ${e.data}`);
          break;
      }
    })
      .then((fn) => {
        unlisten = fn;
      })
      .catch((e) => setError(String(e)));
    return () => unlisten?.();
  }, [appendLog, paintRows]);

  // Size the canvas to the locked mode. Clearing on a mode change is
  // deliberate: the previous image belongs to a different geometry.
  const mode = stateMode(state);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !mode) return;
    const key = `${mode.width}x${mode.height}`;
    if (canvasMode.current === key) return;
    canvasMode.current = key;
    canvas.width = mode.width;
    canvas.height = mode.height;
    const ctx = canvas.getContext("2d");
    if (ctx) {
      ctx.fillStyle = "#000";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
    }
  }, [mode]);

  async function toggle() {
    setError(null);
    try {
      if (running) {
        await stopRx();
        setRunning(false);
        setState({ kind: "idle" });
      } else {
        const started = await startRx(device || null);
        setRunning(true);
        appendLog(
          `capturing from ${started.device_name} at ${started.device_rate} Hz → ${started.codec_rate} Hz`,
        );
      }
    } catch (e) {
      setError(String(e));
      setRunning(false);
    }
  }

  const progress = progressPercent(state);
  const quality = qualityPercent(state);
  const slant = describeSlant(state);

  return (
    <main>
      <header>
        <h1>SSTVAF</h1>
        <p className="sub">Slow-scan television — desktop</p>
      </header>

      <section className="controls">
        <select value={device} onChange={(e) => setDevice(e.target.value)} disabled={running}>
          {devices.length === 0 && <option value="">No input devices found</option>}
          {devices.map((d) => (
            <option key={d.id} value={d.id}>
              {describeDevice(d)}
            </option>
          ))}
        </select>
        <button onClick={toggle} className={running ? "stop" : "start"}>
          {running ? "Stop" : "Start receiving"}
        </button>
      </section>

      {error && <p className="error">{error}</p>}

      <section className="rx">
        <div className="status">
          <span className={`dot ${state.kind}`} />
          <strong>{describeRxState(state)}</strong>
          {quality !== null && <span className="dim">quality {quality}%</span>}
          {slant && <span className="dim">slant {slant}</span>}
        </div>

        {progress !== null && (
          <div className="progress">
            <div className="bar" style={{ width: `${progress}%` }} />
          </div>
        )}

        <div className="frame">
          <canvas ref={canvasRef} width={320} height={240} />
        </div>
      </section>

      <section>
        <h2>Log</h2>
        <ul className="log">
          {log.length === 0 && <li className="dim">Nothing yet.</li>}
          {log.map((line, i) => (
            <li key={`${i}-${line}`}>{line}</li>
          ))}
        </ul>
      </section>
    </main>
  );
}
