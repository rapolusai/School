import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import {
  consentPage,
  noticeAdmin,
  PRIVACY_STAFF,
  READY_EXPORT,
  requestDetail,
  requestPage,
  requestRow,
} from "@/test/privacy-fixtures";
import { renderAs } from "@/test/render";
import { ConsentsView } from "./consents-view";
import { NoticeAdminView } from "./notice-admin-view";
import { RequestDetailView } from "./request-detail-view";
import { RequestsView } from "./requests-view";

const mocks = vi.hoisted(() => ({
  requests: vi.fn(),
  request: vi.fn(),
  staff: vi.fn(),
  assign: vi.fn(),
  reply: vi.fn(),
  createExport: vi.fn(),
  erase: vi.fn(),
  close: vi.fn(),
  noticeAdmin: vi.fn(),
  publishNotice: vi.fn(),
  updateOfficer: vi.fn(),
  consents: vi.fn(),
  recordPaperConsent: vi.fn(),
}));

vi.mock("@/lib/privacy-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/privacy-api")>();
  return { ...actual, privacyApi: { ...actual.privacyApi, ...mocks } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/privacy",
}));

beforeEach(() => {
  mocks.staff.mockResolvedValue([
    { id: "u1", name: "Priya Nair" },
    { id: "u2", name: "Lakshmi Iyer" },
  ]);
});

describe("RequestsView", () => {
  it("lists open requests with who raised them, about whom and when they are due", async () => {
    mocks.requests.mockResolvedValue(
      requestPage([
        requestRow(),
        requestRow({
          id: "r2",
          type: "ERASURE",
          status: "IN_PROGRESS",
          studentName: "Kabir Khan",
          requesterName: "Farah Khan",
          assignedTo: { id: "u2", name: "Lakshmi Iyer" },
          daysLeft: -2,
          overdue: true,
        }),
      ]),
    );
    renderAs(PRIVACY_STAFF, <RequestsView />);

    const table = await screen.findByTestId("requests-table");
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByRole("link", { name: "Anitha Sharma" })).toHaveAttribute("href", "/app/privacy/requests/r1");
    expect(within(rows[1]).getByText("Copy of data")).toBeInTheDocument();
    expect(within(rows[1]).getByText("22 days left")).toBeInTheDocument();
    expect(within(rows[2]).getByText("2 days overdue")).toBeInTheDocument();
    expect(within(rows[2]).getByText("Lakshmi Iyer")).toBeInTheDocument();
    expect(within(screen.getByTestId("request-counts")).getByText("Overdue")).toBeInTheDocument();
    expect(mocks.requests).toHaveBeenCalledWith({ status: "OPEN", type: undefined, q: undefined, page: 0, size: 25 });
  });

  it("filters by type", async () => {
    mocks.requests.mockResolvedValue(requestPage([]));
    renderAs(PRIVACY_STAFF, <RequestsView />);
    await screen.findByTestId("requests-empty");
    fireEvent.change(screen.getByRole("combobox", { name: "Type" }), { target: { value: "ERASURE" } });
    await waitFor(() => expect(mocks.requests).toHaveBeenLastCalledWith(expect.objectContaining({ type: "ERASURE" })));
    expect(await screen.findByText("No requests match.")).toBeInTheDocument();
  });
});

describe("RequestDetailView", () => {
  it("makes a data file for an access request and shows its expiry", async () => {
    const user = userEvent.setup();
    mocks.request.mockResolvedValue(requestDetail());
    mocks.createExport.mockResolvedValue(
      requestDetail({ status: "IN_PROGRESS", export: READY_EXPORT, exports: [READY_EXPORT] }),
    );
    renderAs(PRIVACY_STAFF, <RequestDetailView id="r1" />);

    const card = await screen.findByTestId("export-card");
    expect(within(card).getByText("No file has been made yet.")).toBeInTheDocument();
    await user.click(within(card).getByRole("button", { name: "Make the data file" }));
    await waitFor(() => expect(mocks.createExport).toHaveBeenCalledWith("r1"));
    expect(await within(card).findByText(READY_EXPORT.fileName)).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Make a fresh file" })).toBeInTheDocument();
    expect(screen.queryByTestId("erase-card")).not.toBeInTheDocument();
  });

  it("assigns the request and replies to the parent", async () => {
    const user = userEvent.setup();
    mocks.request.mockResolvedValue(requestDetail());
    mocks.assign.mockResolvedValue(requestDetail({ status: "IN_PROGRESS", assignedTo: { id: "u2", name: "Lakshmi Iyer" } }));
    mocks.reply.mockResolvedValue(requestDetail({ status: "IN_PROGRESS" }));
    renderAs(PRIVACY_STAFF, <RequestDetailView id="r1" />);

    const assignTo = await screen.findByRole("combobox", { name: "Assign to" });
    await waitFor(() => expect(within(assignTo).getByRole("option", { name: "Lakshmi Iyer" })).toBeInTheDocument());
    await user.selectOptions(assignTo, "u2");
    await user.click(screen.getByRole("button", { name: "Assign" }));
    await waitFor(() => expect(mocks.assign).toHaveBeenCalledWith("r1", "u2"));

    await user.click(screen.getByRole("button", { name: "Send reply" }));
    expect(await screen.findByText("Fill in this field.")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "Reply to the parent" }), "We are preparing the file.");
    await user.click(screen.getByRole("button", { name: "Send reply" }));
    await waitFor(() => expect(mocks.reply).toHaveBeenCalledWith("r1", "We are preparing the file."));
  });

  it("erases a left student's data only after the admission number is typed", async () => {
    const user = userEvent.setup();
    const erasure = requestDetail({
      id: "r3",
      type: "ERASURE",
      studentId: "st3",
      studentName: "Kabir Khan",
      admissionNo: "AKS/2026/003",
      studentStatus: "WITHDRAWN",
      canErase: true,
    });
    mocks.request.mockResolvedValue(erasure);
    mocks.erase.mockResolvedValue({ ...erasure, canErase: false, erasedAt: "2026-10-09T06:00:00Z", studentName: "Erased student" });
    renderAs(PRIVACY_STAFF, <RequestDetailView id="r3" />);

    const card = await screen.findByTestId("erase-card");
    await user.click(within(card).getByRole("button", { name: "Erase data…" }));
    const dialog = await screen.findByRole("dialog", { name: "Erase the student's data" });
    const confirm = within(dialog).getByRole("textbox", { name: "Type the admission number AKS/2026/003 to confirm" });
    const submit = within(dialog).getByRole("button", { name: "Erase for good" });
    await user.type(confirm, "AKS/2026/00");
    expect(submit).toBeDisabled();
    await user.type(confirm, "3");
    expect(submit).toBeEnabled();
    await user.click(submit);
    await waitFor(() => expect(mocks.erase).toHaveBeenCalledWith("r3", "AKS/2026/003"));
    expect(await within(card).findByText(/Erased on/)).toBeInTheDocument();
  });

  it("explains that a student still at school cannot be erased, and only allows declining", async () => {
    const user = userEvent.setup();
    const erasure = requestDetail({ id: "r4", type: "ERASURE", canErase: false });
    mocks.request.mockResolvedValue(erasure);
    mocks.close.mockResolvedValue({ ...erasure, status: "CLOSED", resolution: "DECLINED", closingNote: "Still at school." });
    renderAs(PRIVACY_STAFF, <RequestDetailView id="r4" />);

    expect(await screen.findByTestId("erase-not-left")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close request…" }));
    const dialog = await screen.findByRole("dialog", { name: "Close the request" });
    expect(within(dialog).getByRole("radio", { name: "Completed" })).toBeDisabled();
    await user.click(within(dialog).getByRole("radio", { name: "Declined" }));
    await user.click(within(dialog).getByRole("button", { name: "Close request" }));
    expect(await within(dialog).findByText("Say why the request is declined.")).toBeInTheDocument();
    await user.type(within(dialog).getByRole("textbox", { name: /Note to the parent/ }), "Still at school.");
    await user.click(within(dialog).getByRole("button", { name: "Close request" }));
    await waitFor(() => expect(mocks.close).toHaveBeenCalledWith("r4", { resolution: "DECLINED", note: "Still at school." }));
  });

  it("says when a request does not exist", async () => {
    mocks.request.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(PRIVACY_STAFF, <RequestDetailView id="nope" />);
    expect(await screen.findByText("This request could not be found.")).toBeInTheDocument();
  });
});

describe("NoticeAdminView", () => {
  it("publishes the first version from the standard template", async () => {
    const user = userEvent.setup();
    mocks.noticeAdmin.mockResolvedValue(
      noticeAdmin({ current: null, versions: [], draftEn: "## Template", draftHi: "## नमूना", draftIsTemplate: true }),
    );
    mocks.publishNotice.mockResolvedValue({});
    renderAs(PRIVACY_STAFF, <NoticeAdminView />);

    expect(await screen.findByTestId("template-hint")).toBeInTheDocument();
    expect(screen.getByText(/No notice published yet/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Publish version 1" }));
    await waitFor(() =>
      expect(mocks.publishNotice).toHaveBeenCalledWith({ bodyEn: "## Template", bodyHi: "## नमूना", changeSummary: null }),
    );
  });

  it("asks what changed before publishing a new version", async () => {
    const user = userEvent.setup();
    mocks.noticeAdmin.mockResolvedValue(noticeAdmin());
    renderAs(PRIVACY_STAFF, <NoticeAdminView />);

    expect(await screen.findByTestId("current-notice")).toHaveTextContent("Version 2");
    expect(within(screen.getByTestId("notice-versions")).getAllByRole("listitem")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Publish version 3" }));
    expect(await screen.findByText("Say what changed, so parents know why they are asked again.")).toBeInTheDocument();
    expect(mocks.publishNotice).not.toHaveBeenCalled();
  });

  it("needs a grievance officer before publishing", async () => {
    const user = userEvent.setup();
    mocks.noticeAdmin.mockResolvedValue(noticeAdmin({ officer: null, current: null, versions: [] }));
    renderAs(PRIVACY_STAFF, <NoticeAdminView />);

    await user.click(await screen.findByRole("button", { name: "Publish version 1" }));
    expect(await screen.findByText("Save the grievance officer before publishing the notice.")).toBeInTheDocument();
    expect(mocks.publishNotice).not.toHaveBeenCalled();
  });
});

describe("ConsentsView", () => {
  it("shows coverage and each student's consent", async () => {
    mocks.consents.mockResolvedValue(consentPage());
    renderAs(PRIVACY_STAFF, <ConsentsView />);

    expect(await screen.findByTestId("consent-coverage")).toHaveTextContent(
      "1 of 2 students at school have essential consent for version 2.",
    );
    const rows = within(screen.getByTestId("consents-table")).getAllByRole("row");
    expect(within(rows[1]).getAllByText("Agreed")).toHaveLength(2);
    expect(within(rows[2]).getAllByText("Not asked yet")).toHaveLength(3);
  });

  it("records a signed paper form", async () => {
    const user = userEvent.setup();
    mocks.consents.mockResolvedValue(consentPage());
    mocks.recordPaperConsent.mockResolvedValue({});
    renderAs(PRIVACY_STAFF, <ConsentsView />);

    const table = await screen.findByTestId("consents-table");
    await user.click(within(table).getByRole("button", { name: "Paper form: Kabir Khan" }));
    const dialog = await screen.findByRole("dialog", { name: "Record a signed paper form" });
    await user.click(within(dialog).getByRole("button", { name: "Record form" }));
    expect(await within(dialog).findByText("Fill in this field.")).toBeInTheDocument();

    await user.type(within(dialog).getByRole("textbox", { name: /Signed by/ }), "Farah Khan");
    await user.type(within(dialog).getByRole("textbox", { name: /Form reference/ }), "File 12");
    await user.click(within(dialog).getByRole("checkbox", { name: /WhatsApp/ }));
    await user.click(within(dialog).getByRole("button", { name: "Record form" }));
    await waitFor(() =>
      expect(mocks.recordPaperConsent).toHaveBeenCalledWith("st3", {
        givenByName: "Farah Khan",
        signedOn: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
        paperReference: "File 12",
        photos: false,
        whatsapp: true,
      }),
    );
  });

  it("asks for a notice before consent can be collected", async () => {
    mocks.consents.mockResolvedValue(consentPage({ noticeVersion: null, essentialGiven: 0 }));
    renderAs(PRIVACY_STAFF, <ConsentsView />);
    expect(await screen.findByText("Publish a privacy notice to start collecting consent.")).toBeInTheDocument();
    expect(within(screen.getByTestId("consents-table")).getByRole("button", { name: "Paper form: Kabir Khan" })).toBeDisabled();
  });
});
