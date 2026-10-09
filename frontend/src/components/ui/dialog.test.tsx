import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Dialog } from "./dialog";

function Harness({ onClose }: { onClose?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      <Dialog
        open={open}
        onClose={() => {
          onClose?.();
          setOpen(false);
        }}
        title="Add a user"
        description="Fill in the details."
      >
        <input aria-label="Name" />
        <input aria-label="Email" />
        <button type="submit">Save</button>
      </Dialog>
    </>
  );
}

describe("Dialog", () => {
  it("is a labelled modal that focuses its first field", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "Open" }));

    const dialog = screen.getByRole("dialog", { name: "Add a user" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("Fill in the details.");
    expect(screen.getByLabelText("Name")).toHaveFocus();
  });

  it("closes on Escape and returns focus to the opener", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    const opener = screen.getByRole("button", { name: "Open" });
    await user.click(opener);
    await user.keyboard("{Escape}");

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it("traps Tab and Shift+Tab inside the dialog", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "Open" }));

    const close = screen.getByRole("button", { name: "Close" });
    const name = screen.getByLabelText("Name");
    const save = screen.getByRole("button", { name: "Save" });

    save.focus();
    await user.tab();
    expect(close).toHaveFocus(); // wrapped from last to first

    await user.tab({ shift: true });
    expect(save).toHaveFocus(); // wrapped from first to last

    await user.tab({ shift: true });
    await user.tab({ shift: true });
    expect(name).toHaveFocus();
  });

  it("closes when the backdrop is clicked but not when the panel is", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    await user.click(screen.getByRole("button", { name: "Open" }));

    await user.click(screen.getByRole("dialog"));
    expect(onClose).not.toHaveBeenCalled();

    const scrim = screen.getByRole("dialog").parentElement!;
    await user.click(scrim);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
