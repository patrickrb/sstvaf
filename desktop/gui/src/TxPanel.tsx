import { useCallback, useEffect, useRef, useState } from "react";

import {
  AudioDevice,
  describeDevice,
  formatDuration,
  listAudioOutputs,
  listModes,
  ModeInfo,
  onTxEvent,
  startTx,
  stopTx,
} from "./ipc";
import {
  describeTxEvent,
  letterboxRect,
  rgbaToArgb,
  txPercent,
  type TxEvent,
} from "./tx";

interface TxProgress {
  elapsed: number;
  total: number;
  fraction: number;
}

/**
 * The Transmit section: pick an image, letterbox it to the selected mode's
 * frame on a canvas (which doubles as the preview — what you see is exactly
 * what is encoded), choose an output device, and send.
 */
export default function TxPanel() {
  const [modes, setModes] = useState<ModeInfo[]>([]);
  const [modeId, setModeId] = useState<number>(0);
  const [devices, setDevices] = useState<AudioDevice[]>([]);
  const [device, setDevice] = useState<string>("");
  const [image, setImage] = useState<HTMLImageElement | null>(null);
  const [imageName, setImageName] = useState<string>("");
  const [sending, setSending] = useState(false);
  const [progress, setProgress] = useState<TxProgress | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const canvasRef = useRef<HTMLCanvasElement>(null);

  const mode = modes.find((m) => m.id === modeId) ?? null;

  useEffect(() => {
    listModes()
      .then((m) => {
        setModes(m);
        if (m.length > 0) setModeId(m[0].id);
      })
      .catch((e) => setError(String(e)));
    listAudioOutputs()
      .then((d) => {
        setDevices(d);
        const def = d.find((x) => x.is_default);
        if (def) setDevice(def.id);
      })
      .catch((e) => setError(String(e)));
  }, []);

  // Redraw the frame whenever the image or the mode's geometry changes: black
  // background, image letterboxed inside. This canvas is the exact pixel
  // buffer that gets encoded.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !mode) return;
    canvas.width = mode.width;
    canvas.height = mode.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, mode.width, mode.height);
    if (image) {
      const r = letterboxRect(image.width, image.height, mode.width, mode.height);
      ctx.drawImage(image, r.x, r.y, r.width, r.height);
    }
  }, [image, mode]);

  const handleTxEvent = useCallback((e: TxEvent) => {
    switch (e.event) {
      case "progress":
        setProgress({
          elapsed: e.data.elapsed_seconds,
          total: e.data.total_seconds,
          fraction: e.data.fraction,
        });
        break;
      case "complete":
        setSending(false);
        setProgress(null);
        break;
      case "cancelled":
        setSending(false);
        setProgress(null);
        break;
      case "error":
        setError(e.data);
        break;
    }
    const line = describeTxEvent(e);
    if (line) setStatus(line);
  }, []);

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    // Same lifecycle dance as the RX listener in App.tsx: if cleanup runs
    // before the subscription resolves, detach as soon as the handle arrives.
    let cancelled = false;
    onTxEvent(handleTxEvent)
      .then((fn) => {
        if (cancelled) {
          fn();
        } else {
          unlisten = fn;
        }
      })
      .catch((e) => setError(String(e)));
    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, [handleTxEvent]);

  function pickImage(e: React.ChangeEvent<HTMLInputElement>) {
    setError(null);
    const file = e.target.files?.[0];
    if (!file) return;
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      setImage(img);
      setImageName(file.name);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      setError(`could not load ${file.name}`);
    };
    img.src = url;
  }

  async function send() {
    setError(null);
    const canvas = canvasRef.current;
    if (!canvas || !mode || !image) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    try {
      // The canvas holds the letterboxed frame at exactly the mode's
      // dimensions — its pixels are the transmission.
      const data = ctx.getImageData(0, 0, mode.width, mode.height).data;
      const pixels = rgbaToArgb(data);
      setSending(true);
      const started = await startTx(device || null, mode.id, pixels);
      setProgress({ elapsed: 0, total: started.total_seconds, fraction: 0 });
      setStatus(
        `transmitting ${mode.name} on ${started.device_name} at ${started.sample_rate} Hz — ${formatDuration(started.total_seconds)}`,
      );
    } catch (e) {
      setSending(false);
      setProgress(null);
      setError(String(e));
    }
  }

  async function stop() {
    try {
      await stopTx();
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <section className="tx">
      <h2>Transmit</h2>

      <div className="controls">
        <label className="file-pick">
          <input type="file" accept="image/*" onChange={pickImage} disabled={sending} />
          {imageName ? imageName : "Choose image…"}
        </label>
        <select
          value={modeId}
          onChange={(e) => setModeId(Number(e.target.value))}
          disabled={sending}
        >
          {modes.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name} ({m.width}×{m.height}, {formatDuration(m.tx_seconds)})
            </option>
          ))}
        </select>
        <select value={device} onChange={(e) => setDevice(e.target.value)} disabled={sending}>
          {devices.length === 0 && <option value="">No output devices found</option>}
          {devices.map((d) => (
            <option key={d.id} value={d.id}>
              {describeDevice(d)}
            </option>
          ))}
        </select>
        {sending ? (
          <button onClick={stop} className="stop">
            Stop
          </button>
        ) : (
          <button onClick={send} disabled={!image || !mode}>
            Send
          </button>
        )}
      </div>

      {error && <p className="error">{error}</p>}
      {status && <p className="dim">{status}</p>}

      {progress && (
        <div className="progress">
          <div className="bar" style={{ width: `${txPercent(progress.fraction)}%` }} />
        </div>
      )}
      {progress && (
        <p className="dim">
          {formatDuration(progress.elapsed)} / {formatDuration(progress.total)}
        </p>
      )}

      <div className="frame">
        {/* The preview *is* the TX buffer: mode-sized, letterboxed. */}
        <canvas ref={canvasRef} width={320} height={256} />
      </div>
      {!image && <p className="dim">Pick an image to preview the transmission frame.</p>}
    </section>
  );
}
