/**
 * Razorpay Checkout (https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/): Razorpay's own
 * script opens its payment window over the page. The order is created by our API first; Razorpay hands back the
 * payment id and a signature, which our API verifies before it issues the receipt. Nothing about the payer's card,
 * UPI or bank passes through this app. The script and its frames are allowed by the Content-Security-Policy in
 * next.config.ts.
 */

export const CHECKOUT_SCRIPT = "https://checkout.razorpay.com/v1/checkout.js";

export type CheckoutOrder = {
  key: string;
  gatewayOrderId: string;
  amountPaise: number;
  currency: string;
  /** Shown at the top of Razorpay's window, e.g. the school's name. */
  name: string;
  description: string;
};

export type CheckoutProof = { gatewayPaymentId: string; signature: string };

type RazorpayResponse = { razorpay_payment_id: string; razorpay_order_id: string; razorpay_signature: string };

type RazorpayOptions = {
  key: string;
  order_id: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  handler: (response: RazorpayResponse) => void;
  modal: { ondismiss: () => void; escape: boolean };
  theme: { color: string };
};

type RazorpayInstance = { open: () => void; on: (event: string, handler: () => void) => void };

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => RazorpayInstance;
  }
}

let loading: Promise<void> | null = null;

/** Adds Razorpay's script once per page; later calls reuse it. */
export function loadCheckoutScript(): Promise<void> {
  if (typeof window !== "undefined" && window.Razorpay) return Promise.resolve();
  if (!loading) {
    loading = new Promise<void>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = CHECKOUT_SCRIPT;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => {
        loading = null;
        script.remove();
        reject(new Error("Razorpay Checkout could not be loaded"));
      };
      document.head.appendChild(script);
    });
  }
  return loading;
}

/**
 * Opens Razorpay's window for the order. Resolves with the payment id and signature when the payer has paid, or
 * null when they closed the window without paying. Rejects when the script cannot be loaded.
 */
export async function openRazorpayCheckout(order: CheckoutOrder): Promise<CheckoutProof | null> {
  await loadCheckoutScript();
  const Razorpay = window.Razorpay;
  if (!Razorpay) throw new Error("Razorpay Checkout is not available");
  return new Promise<CheckoutProof | null>((resolve) => {
    let settled = false;
    const checkout = new Razorpay({
      key: order.key,
      order_id: order.gatewayOrderId,
      amount: order.amountPaise,
      currency: order.currency,
      name: order.name,
      description: order.description,
      handler: (response) => {
        settled = true;
        resolve({ gatewayPaymentId: response.razorpay_payment_id, signature: response.razorpay_signature });
      },
      modal: {
        escape: true,
        ondismiss: () => {
          if (!settled) resolve(null);
        },
      },
      theme: { color: "#c98a12" },
    });
    checkout.open();
  });
}

/** Test helper: forget the loaded script between tests. */
export function __resetCheckoutForTests(): void {
  loading = null;
}
