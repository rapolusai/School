"use client";

import { Check } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

type ToastContextValue = { toast: (message: string) => void };

const ToastContext = createContext<ToastContextValue>({ toast: () => {} });

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [message, setMessage] = useState<{ id: number; text: string } | null>(null);

  const toast = useCallback((text: string) => {
    setMessage({ id: Date.now(), text });
  }, []);

  useEffect(() => {
    if (!message) return;
    const timer = window.setTimeout(() => setMessage(null), 3200);
    return () => window.clearTimeout(timer);
  }, [message]);

  const value = useMemo(() => ({ toast }), [toast]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div role="status" aria-live="polite" className="contents">
        {message ? (
          <div key={message.id} className="toast">
            <Check size={18} aria-hidden="true" />
            {message.text}
          </div>
        ) : null}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  return useContext(ToastContext);
}
