import { afterEach, describe, expect, it, vi } from "vitest";
import { __resetCheckoutForTests, CHECKOUT_SCRIPT, loadCheckoutScript, openRazorpayCheckout } from "./razorpay-checkout";

const ORDER = {
  key: "rzp_test_key",
  gatewayOrderId: "order_RZP1",
  amountPaise: 1_160_000,
  currency: "INR",
  name: "Sunrise Public School",
  description: "Arjun Sharma · Quarter 2",
};

type Options = {
  handler: (r: { razorpay_payment_id: string; razorpay_order_id: string; razorpay_signature: string }) => void;
  modal: { ondismiss: () => void };
};

function fakeRazorpay(act: (options: Options) => void) {
  const created: Options[] = [];
  window.Razorpay = vi.fn().mockImplementation(function (this: unknown, options: Options) {
    created.push(options);
    return { open: () => act(options), on: vi.fn() };
  }) as unknown as typeof window.Razorpay;
  return created;
}

afterEach(() => {
  delete window.Razorpay;
  __resetCheckoutForTests();
  document.head.querySelectorAll(`script[src="${CHECKOUT_SCRIPT}"]`).forEach((s) => s.remove());
});

describe("Razorpay checkout", () => {
  it("adds Razorpay's script once and resolves when it loads", async () => {
    const first = loadCheckoutScript();
    const second = loadCheckoutScript();
    const scripts = document.head.querySelectorAll(`script[src="${CHECKOUT_SCRIPT}"]`);
    expect(scripts).toHaveLength(1);
    scripts[0].dispatchEvent(new Event("load"));
    await expect(first).resolves.toBeUndefined();
    await expect(second).resolves.toBeUndefined();
  });

  it("rejects when the script cannot be loaded, and tries again next time", async () => {
    const attempt = loadCheckoutScript();
    document.head.querySelector(`script[src="${CHECKOUT_SCRIPT}"]`)!.dispatchEvent(new Event("error"));
    await expect(attempt).rejects.toThrow("could not be loaded");
    expect(document.head.querySelectorAll(`script[src="${CHECKOUT_SCRIPT}"]`)).toHaveLength(0);
  });

  it("returns the payment id and signature when the payer pays", async () => {
    const created = fakeRazorpay((o) =>
      o.handler({ razorpay_payment_id: "pay_1", razorpay_order_id: "order_RZP1", razorpay_signature: "sig" }),
    );
    await expect(openRazorpayCheckout(ORDER)).resolves.toEqual({ gatewayPaymentId: "pay_1", signature: "sig" });
    expect(created[0]).toMatchObject({ key: "rzp_test_key", order_id: "order_RZP1", amount: 1_160_000 });
  });

  it("returns null when the payer closes the window", async () => {
    fakeRazorpay((o) => o.modal.ondismiss());
    await expect(openRazorpayCheckout(ORDER)).resolves.toBeNull();
  });
});
