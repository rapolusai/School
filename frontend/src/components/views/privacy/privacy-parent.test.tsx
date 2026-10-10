import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import {
  ARJUN,
  DIYA,
  NEEDS_CONSENT,
  PARENT,
  parentPrivacy,
  publicNotice,
  READY_EXPORT,
  requestDetail,
  requestRow,
  state,
} from "@/test/privacy-fixtures";
import { renderAs } from "@/test/render";
import { MyPrivacyView } from "./my-privacy-view";
import { MyRequestView } from "./my-request-view";
import { PublicNoticeView } from "./public-notice-view";

const mocks = vi.hoisted(() => ({
  mine: vi.fn(),
  myRequests: vi.fn(),
  myRequest: vi.fn(),
  change: vi.fn(),
  accept: vi.fn(),
  submit: vi.fn(),
  myReply: vi.fn(),
  publicNotice: vi.fn(),
  downloadExport: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/lib/privacy-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/privacy-api")>();
  const { mine, myRequests, myRequest, change, accept, submit, myReply, publicNotice } = mocks;
  return {
    ...actual,
    privacyApi: { ...actual.privacyApi, mine, myRequests, myRequest, change, accept, submit, myReply, publicNotice },
    downloadExport: mocks.downloadExport,
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: mocks.push }),
  usePathname: () => "/app/my-privacy",
}));

beforeEach(() => {
  mocks.mine.mockResolvedValue(parentPrivacy());
  mocks.myRequests.mockResolvedValue([]);
});

describe("MyPrivacyView", () => {
  it("shows each child's choices and lets the parent withdraw one", async () => {
    const user = userEvent.setup();
    mocks.change.mockResolvedValue(
      parentPrivacy({ children: [{ ...ARJUN, purposes: [state("ESSENTIAL", "GIVEN"), state("PHOTOS", "WITHDRAWN"), state("WHATSAPP", "DECLINED")] }, DIYA] }),
    );
    renderAs(PARENT, <MyPrivacyView />, ["PARENT"]);

    const arjun = await screen.findByTestId("child-consent-st1");
    expect(within(arjun).getByText("Agreed")).toBeInTheDocument();
    const photos = within(arjun).getByRole("checkbox", { name: /Photos in school albums/ });
    expect(photos).toBeChecked();
    expect(within(arjun).getByRole("checkbox", { name: /WhatsApp/ })).not.toBeChecked();
    await user.click(photos);
    await waitFor(() => expect(mocks.change).toHaveBeenCalledWith("st1", "PHOTOS", false));
    await waitFor(() => expect(within(screen.getByTestId("child-consent-st1")).getByRole("checkbox", { name: /Photos/ })).not.toBeChecked());
    expect(await screen.findByText("Consent withdrawn.")).toBeInTheDocument();
    expect(screen.getByTestId("my-requests-empty")).toBeInTheDocument();
  });

  it("asks for the notice to be accepted on the page itself", async () => {
    mocks.mine.mockResolvedValue(NEEDS_CONSENT);
    renderAs(PARENT, <MyPrivacyView />, ["PARENT"]);
    const gate = await screen.findByTestId("privacy-gate");
    expect(within(gate).getByRole("heading", { level: 2 })).toBeInTheDocument();
    expect(within(gate).queryByRole("link", { name: /Raise a request instead/ })).not.toBeInTheDocument();
    expect(within(screen.getByTestId("child-consent-st1")).getByRole("checkbox", { name: /Photos/ })).toBeDisabled();
  });

  it("raises a request about a child and opens it", async () => {
    const user = userEvent.setup();
    mocks.submit.mockResolvedValue(requestDetail({ id: "r9", dueOn: "2026-11-08" }));
    renderAs(PARENT, <MyPrivacyView />, ["PARENT"]);

    await user.click(await screen.findByRole("button", { name: "New request" }));
    const dialog = await screen.findByRole("dialog", { name: "New request" });
    await user.selectOptions(within(dialog).getByRole("combobox", { name: "What do you want?" }), "ERASURE");
    expect(within(within(dialog).getByRole("combobox", { name: "About whom?" })).queryByRole("option", { name: "Me (the parent)" })).not.toBeInTheDocument();
    await user.selectOptions(within(dialog).getByRole("combobox", { name: "About whom?" }), "st2");
    await user.click(within(dialog).getByRole("button", { name: "Raise request" }));

    await waitFor(() =>
      expect(mocks.submit).toHaveBeenCalledWith({ type: "ERASURE", subject: "CHILD", studentId: "st2", details: null }),
    );
    await waitFor(() => expect(mocks.push).toHaveBeenCalledWith("/app/my-privacy/requests/r9"));
  });

  it("lists the parent's requests with their status", async () => {
    mocks.myRequests.mockResolvedValue([requestRow({ status: "IN_PROGRESS" })]);
    renderAs(PARENT, <MyPrivacyView />, ["PARENT"]);
    const list = await screen.findByTestId("my-requests");
    expect(within(list).getByRole("link")).toHaveAttribute("href", "/app/my-privacy/requests/r1");
    expect(within(list).getByText("In progress")).toBeInTheDocument();
  });
});

describe("MyRequestView", () => {
  it("lets the parent download a ready data file and reply", async () => {
    const user = userEvent.setup();
    mocks.myRequest.mockResolvedValue(requestDetail({ status: "IN_PROGRESS", export: READY_EXPORT, exports: [READY_EXPORT] }));
    mocks.myReply.mockResolvedValue(requestDetail({ status: "IN_PROGRESS", export: READY_EXPORT, exports: [READY_EXPORT] }));
    mocks.downloadExport.mockResolvedValue(undefined);
    renderAs(PARENT, <MyRequestView id="r1" />, ["PARENT"]);

    const file = await screen.findByTestId("my-export");
    await user.click(within(file).getByRole("button", { name: "Download data file" }));
    await waitFor(() =>
      expect(mocks.downloadExport).toHaveBeenCalledWith("/api/me/privacy/requests/r1/export", READY_EXPORT.fileName),
    );
    await user.type(screen.getByRole("textbox", { name: "Reply to the school" }), "Thank you.");
    await user.click(screen.getByRole("button", { name: "Send reply" }));
    await waitFor(() => expect(mocks.myReply).toHaveBeenCalledWith("r1", "Thank you."));
    expect(within(screen.getByTestId("request-timeline")).getByText("Request raised")).toBeInTheDocument();
  });

  it("shows the outcome of a closed request without a reply box", async () => {
    mocks.myRequest.mockResolvedValue(
      requestDetail({ status: "CLOSED", resolution: "DECLINED", closingNote: "Arjun is still at school." }),
    );
    renderAs(PARENT, <MyRequestView id="r1" />, ["PARENT"]);
    expect(await screen.findByText("Arjun is still at school.")).toBeInTheDocument();
    expect(screen.getByText("Declined")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("says when the request is not the parent's", async () => {
    mocks.myRequest.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(PARENT, <MyRequestView id="r1" />, ["PARENT"]);
    expect(await screen.findByText("This request could not be found.")).toBeInTheDocument();
  });
});

describe("PublicNoticeView", () => {
  it("shows the school's current notice and grievance officer to anyone", async () => {
    mocks.publicNotice.mockResolvedValue(publicNotice());
    render(<PublicNoticeView schoolCode="sunrise-public" />);

    expect(await screen.findByTestId("notice-school")).toHaveTextContent("Sunrise Public School");
    expect(screen.getByRole("heading", { name: "What we collect" })).toBeInTheDocument();
    expect(within(screen.getByTestId("grievance-officer")).getByRole("link", { name: "grievance@school.test" })).toHaveAttribute(
      "href",
      "mailto:grievance@school.test",
    );
    expect(mocks.publicNotice).toHaveBeenCalledWith("sunrise-public", undefined);
  });

  it("reads an older version and goes back to the current one", async () => {
    const user = userEvent.setup();
    mocks.publicNotice.mockImplementation(async (_code: string, version?: number) =>
      version === 1 ? publicNotice({ version: 1, changeSummary: null, bodyEn: "## First version" }) : publicNotice(),
    );
    render(<PublicNoticeView schoolCode="sunrise-public" />);

    const versions = await screen.findByTestId("public-versions");
    await user.click(within(versions).getByRole("button", { name: "Read" }));
    expect(await screen.findByRole("heading", { name: "First version" })).toBeInTheDocument();
    expect(screen.getByText("You are reading version 1. The current version is 2.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Read the current version" }));
    expect(await screen.findByRole("heading", { name: "What we collect" })).toBeInTheDocument();
  });

  it("explains a school without a notice", async () => {
    mocks.publicNotice.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    render(<PublicNoticeView schoolCode="nope" />);
    expect(await screen.findByText("Notice not found")).toBeInTheDocument();
  });
});
