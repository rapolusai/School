import { useCallback, useEffect, useEffectEvent, useState } from "react";
import { toApiError, type ApiError } from "./api";

type Settled<T> = { token: string; data?: T; error?: ApiError };

export type ApiData<T> = {
  data: T | undefined;
  error: ApiError | undefined;
  loading: boolean;
  reload: () => void;
};

/**
 * Load data for a component. `key` identifies the request: pass null to skip loading
 * (e.g. when the user lacks the permission). Previous data stays visible while reloading.
 */
export function useApiData<T>(key: string | null, loader: () => Promise<T>): ApiData<T> {
  const [nonce, setNonce] = useState(0);
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const load = useEffectEvent(() => loader());

  const token = key === null ? null : `${key}#${nonce}`;

  useEffect(() => {
    if (token === null) return;
    let cancelled = false;
    load().then(
      (data) => {
        if (!cancelled) setSettled({ token, data });
      },
      (error: unknown) => {
        if (!cancelled) setSettled((prev) => ({ token, data: prev?.data, error: toApiError(error) }));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [token]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const current = settled !== null && settled.token === token;

  return {
    data: token === null ? undefined : settled?.data,
    error: current ? settled.error : undefined,
    loading: token !== null && !current,
    reload,
  };
}
