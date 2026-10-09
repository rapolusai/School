"use client";

import { Download, FileText, Paperclip, X } from "lucide-react";
import { useId, useRef, useState } from "react";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { downloadFile, FILE_ACCEPT, fileProblem, formatBytes, MAX_FILE_BYTES, type FileProblem } from "@/lib/files-api";
import { useI18n, type MessageKey } from "@/lib/i18n";
import type { FileRef } from "@/lib/types";

const PROBLEM: Record<FileProblem, MessageKey> = {
  type: "files.v.type",
  size: "files.v.size",
  empty: "files.v.empty",
};

/** Stored files with a download button each (and a remove button when `onRemove` is given). */
export function AttachmentList({
  files,
  onRemove,
  testId,
}: {
  files: FileRef[];
  onRemove?: (file: FileRef) => void;
  testId?: string;
}) {
  const { t } = useI18n();
  const [error, setError] = useState<string | null>(null);
  if (files.length === 0) return null;
  return (
    <div className="flex flex-col gap-1.5">
      <ul className="file-list" data-testid={testId}>
        {files.map((file) => (
          <li key={file.id} className="file-item">
            <FileText size={18} aria-hidden="true" className="flex-none text-ink-3" />
            <span className="min-w-0 flex-1">
              <span className="file-name">{file.name}</span>
              <span className="text-[12px] text-ink-3">{formatBytes(file.size)}</span>
            </span>
            <button
              type="button"
              className="iconbtn"
              aria-label={t("files.download", { name: file.name })}
              onClick={() => {
                setError(null);
                downloadFile(file).catch((caught: unknown) => setError(errorMessage(toApiError(caught), t)));
              }}
            >
              <Download size={18} aria-hidden="true" />
            </button>
            {onRemove ? (
              <button
                type="button"
                className="iconbtn"
                aria-label={t("files.remove", { name: file.name })}
                onClick={() => onRemove(file)}
              >
                <X size={18} aria-hidden="true" />
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {error ? <p className="field-error">{error}</p> : null}
    </div>
  );
}

/**
 * Picks files to upload, checking each one as the API will (type, 5 MB, not empty) and the total
 * count, before anything is sent. Refused files are named with the reason and not added.
 */
export function FilePicker({
  files,
  onChange,
  max,
  maxBytes = MAX_FILE_BYTES,
  label,
  error,
  name = "files",
}: {
  files: File[];
  onChange: (files: File[]) => void;
  /** How many more files may be added. */
  max: number;
  maxBytes?: number;
  label: string;
  error?: string;
  name?: string;
}) {
  const { t } = useI18n();
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const [refused, setRefused] = useState<string[]>([]);

  const add = (picked: FileList | null) => {
    if (!picked) return;
    const accepted: File[] = [];
    const problems: string[] = [];
    for (const file of Array.from(picked)) {
      const problem = fileProblem(file, maxBytes);
      if (problem) problems.push(t(PROBLEM[problem], { name: file.name }));
      else if (files.length + accepted.length >= max) problems.push(t("files.v.tooMany", { name: file.name, max }));
      else accepted.push(file);
    }
    setRefused(problems);
    if (accepted.length) onChange([...files, ...accepted]);
    if (input.current) input.current.value = "";
  };

  return (
    <div className="field">
      <span className="field-label" id={`${id}-label`}>
        {label}
      </span>
      <div className="flex flex-wrap items-center gap-2">
        <label className={`btn btn-sm${files.length >= max ? " opacity-60" : ""}`} htmlFor={id}>
          <Paperclip size={16} aria-hidden="true" />
          {t("files.choose")}
        </label>
        <input
          ref={input}
          id={id}
          name={name}
          type="file"
          multiple
          accept={FILE_ACCEPT}
          className="sr-only"
          disabled={files.length >= max}
          aria-describedby={`${id}-hint`}
          onChange={(e) => add(e.target.files)}
        />
        <span id={`${id}-hint`} className="field-hint" style={{ margin: 0 }}>
          {t("files.hint", { max })}
        </span>
      </div>
      {files.length > 0 ? (
        <ul className="file-list" aria-labelledby={`${id}-label`}>
          {files.map((file, i) => (
            <li key={`${file.name}:${i}`} className="file-item">
              <FileText size={18} aria-hidden="true" className="flex-none text-ink-3" />
              <span className="min-w-0 flex-1">
                <span className="file-name">{file.name}</span>
                <span className="text-[12px] text-ink-3">{formatBytes(file.size)}</span>
              </span>
              <button
                type="button"
                className="iconbtn"
                aria-label={t("files.remove", { name: file.name })}
                onClick={() => onChange(files.filter((_, j) => j !== i))}
              >
                <X size={18} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {refused.length > 0 ? (
        <ul className="field-error" role="alert" data-testid="file-refused">
          {refused.map((message, i) => (
            <li key={i}>{message}</li>
          ))}
        </ul>
      ) : null}
      {error ? <p className="field-error">{error}</p> : null}
    </div>
  );
}
