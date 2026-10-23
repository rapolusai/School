"use client";

import { Camera, ScanBarcode } from "lucide-react";
import { useEffect, useEffectEvent, useId, useRef, useState, useSyncExternalStore } from "react";
import { Dialog } from "@/components/ui/dialog";
import { useI18n } from "@/lib/i18n";
import { ACCESSION_PATTERN, normalizeAccession } from "@/lib/library-api";

/*
 * The accession number field of the desk and the stock check. A barcode scanner types the number and presses Enter,
 * so Enter sends it and the cursor stays in the field for the next book. On a phone, the browser's BarcodeDetector
 * (where there is one) reads the label through the camera; everywhere else the number is typed.
 */

type DetectedCode = { rawValue: string };
type Detector = { detect: (source: HTMLVideoElement) => Promise<DetectedCode[]> };
type DetectorClass = new (options?: { formats?: string[] }) => Detector;

function detectorClass(): DetectorClass | null {
  if (typeof window === "undefined") return null;
  return (window as unknown as { BarcodeDetector?: DetectorClass }).BarcodeDetector ?? null;
}

const noopSubscribe = () => () => {};

/** True in a browser that can read barcodes through the camera. */
export function useCameraScanning(): boolean {
  return useSyncExternalStore(
    noopSubscribe,
    () => Boolean(detectorClass() && typeof navigator !== "undefined" && navigator.mediaDevices?.getUserMedia),
    () => false,
  );
}

export type ScanFieldProps = {
  label: string;
  name: string;
  /** The button's label (Enter does the same). */
  action: string;
  /** Sends a number; resolves to an error to show under the field, or null when it went through. */
  onScan: (accessionNo: string) => Promise<string | null>;
  hint?: string;
  autoFocus?: boolean;
  testId?: string;
  /** What to say when nothing was scanned (the accession number message unless given; the asset screens name tags). */
  requiredMessage?: string;
};

export function ScanField({ label, name, action, onScan, hint, autoFocus = true, testId, requiredMessage }: ScanFieldProps) {
  const { t } = useI18n();
  const id = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const busyRef = useRef(false);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [camera, setCamera] = useState(false);
  const canUseCamera = useCameraScanning();

  const focus = (select = false) => {
    const input = inputRef.current;
    if (!input) return;
    input.focus();
    if (select) input.select();
  };

  const send = async (raw: string) => {
    if (busyRef.current) return;
    const value = normalizeAccession(raw);
    if (!value) {
      setError(requiredMessage ?? t("library.scan.v.required"));
      focus();
      return;
    }
    if (!ACCESSION_PATTERN.test(value)) {
      setError(t("library.scan.v.pattern"));
      focus(true);
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setError(null);
    let problem: string | null = null;
    try {
      problem = await onScan(value);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
    if (problem) {
      setText(value);
      setError(problem);
      // Keep the number selected, so the next scan replaces it.
      requestAnimationFrame(() => focus(true));
    } else {
      setText("");
      focus();
    }
  };

  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;

  return (
    <form
      method="post"
      className="flex flex-col gap-1.5"
      onSubmit={(event) => {
        event.preventDefault();
        void send(text);
      }}
      noValidate
      data-testid={testId}
    >
      <label htmlFor={id} className="field-label">
        {label}
      </label>
      <div className="scan-row">
        <span className="scan-input">
          <ScanBarcode size={20} aria-hidden="true" />
          <input
            ref={inputRef}
            id={id}
            name={name}
            className="input mono"
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              if (error) setError(null);
            }}
            autoFocus={autoFocus}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            enterKeyHint="go"
            maxLength={30}
            aria-invalid={error ? true : undefined}
            aria-describedby={[hint || !canUseCamera ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined}
            aria-busy={busy || undefined}
          />
        </span>
        <button type="submit" className="btn btn-primary" aria-disabled={busy || undefined}>
          {busy ? t("common.working") : action}
        </button>
        {canUseCamera ? (
          <button
            type="button"
            className="btn"
            onClick={() => setCamera(true)}
            aria-label={t("library.scan.camera")}
            title={t("library.scan.camera")}
          >
            <Camera size={18} aria-hidden="true" />
          </button>
        ) : null}
      </div>
      {hint || !canUseCamera ? (
        <p id={hintId} className="field-hint">
          {[hint, canUseCamera ? null : t("library.scan.typeHint")].filter(Boolean).join(" ")}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="field-error" role="alert">
          {error}
        </p>
      ) : null}
      <CameraDialog
        open={camera}
        onClose={() => {
          setCamera(false);
          focus();
        }}
        onFound={(value) => {
          setCamera(false);
          setText(value);
          void send(value);
        }}
      />
    </form>
  );
}

/** Reads a Code 128 label through the camera; closes with the first accession number it sees. */
function CameraDialog({ open, onClose, onFound }: { open: boolean; onClose: () => void; onFound: (value: string) => void }) {
  const { t } = useI18n();
  const videoRef = useRef<HTMLVideoElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const found = useEffectEvent((value: string) => onFound(value));
  const failed = useEffectEvent(() => setProblem(t("library.scan.camera.denied")));

  useEffect(() => {
    if (!open) return;
    const Detector = detectorClass();
    if (!Detector || !navigator.mediaDevices?.getUserMedia) return;
    let stopped = false;
    let stream: MediaStream | null = null;
    let timer: number | undefined;
    const detector = new Detector({ formats: ["code_128"] });
    navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false }).then(
      async (media) => {
        if (stopped) {
          media.getTracks().forEach((track) => track.stop());
          return;
        }
        stream = media;
        const video = videoRef.current;
        if (!video) return;
        video.srcObject = media;
        await video.play().catch(() => undefined);
        const tick = async () => {
          if (stopped) return;
          try {
            const codes = await detector.detect(video);
            const hit = codes.map((c) => c.rawValue.trim()).find((value) => ACCESSION_PATTERN.test(value));
            if (hit) {
              found(normalizeAccession(hit));
              return;
            }
          } catch {
            // The frame was not ready; try the next one.
          }
          timer = window.setTimeout(tick, 250);
        };
        void tick();
      },
      () => failed(),
    );
    return () => {
      stopped = true;
      window.clearTimeout(timer);
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, [open]);

  return (
    <Dialog
      open={open}
      onClose={() => {
        setProblem(null);
        onClose();
      }}
      title={t("library.scan.camera.title")}
      description={t("library.scan.camera.description")}
      closeLabel={t("common.close")}
    >
      {problem ? (
        <p className="alert alert-warn" role="alert">
          {problem}
        </p>
      ) : (
        <video ref={videoRef} className="scan-video" muted playsInline aria-label={t("library.scan.camera.view")} />
      )}
    </Dialog>
  );
}
