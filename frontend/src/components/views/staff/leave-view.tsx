"use client";

import { Check, ListPlus, Pencil, Plus, Trash2, X } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { leaveApi } from "@/lib/staff-api";
import type { LeaveType, MyLeave, StaffLeaveRequest } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { ApplyLeaveDialog, LeaveActionDialog, LeaveTypeDialog, type LeaveAction } from "./leave-dialogs";
import { BalanceGrid, RequestList } from "./leave-parts";
import { formatNumber, Tabs } from "./staff-shared";

export const LEAVE_TABS = ["mine", "inbox", "types"] as const;
export type LeaveTab = (typeof LEAVE_TABS)[number];

type Acting = { request: StaffLeaveRequest; action: LeaveAction } | null;

/**
 * Leave: one's own balances and requests (apply, cancel), the requests waiting for the caller
 * (approve, reject) and, for staff.manage, the school's leave types.
 */
export function LeaveView({ initialTab }: { initialTab?: string }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canApproveAll = hasPermission(me, PERMISSIONS.leaveApprove);
  const canManage = hasPermission(me, PERMISSIONS.staffManage);
  const [yearId, setYearId] = useState("");
  const [tab, setTab] = useState<LeaveTab>(() =>
    initialTab === "inbox" || (initialTab === "types" && canManage) ? initialTab : "mine",
  );
  const [applying, setApplying] = useState(false);
  const [acting, setActing] = useState<Acting>(null);
  const [inboxAll, setInboxAll] = useState(false);

  const mine = useApiData(`leave:me:${yearId}`, () => leaveApi.mine(yearId || undefined));
  const types = useApiData("leave:types", () => leaveApi.types());
  const inbox = useApiData(tab === "inbox" ? `leave:inbox:${inboxAll}` : null, () => leaveApi.inbox(inboxAll));
  const data = mine.data;
  const waiting = data?.waitingForMe ?? 0;
  const showInbox = canApproveAll || waiting > 0 || tab === "inbox";

  const tabs: { key: LeaveTab; label: string }[] = [{ key: "mine", label: t("leave.tab.mine") }];
  if (showInbox) {
    tabs.push({ key: "inbox", label: waiting > 0 ? t("leave.tab.inboxCount", { count: waiting }) : t("leave.tab.inbox") });
  }
  if (canManage) tabs.push({ key: "types", label: t("leave.tab.types") });

  const afterAction = (request: StaffLeaveRequest, action: LeaveAction) => {
    setActing(null);
    mine.reload();
    if (tab === "inbox") inbox.reload();
    const name = request.userName ?? "";
    toast(
      action === "approve"
        ? t("leave.approved", { name })
        : action === "reject"
          ? t("leave.rejected", { name })
          : t("leave.cancelled"),
    );
  };

  return (
    <>
      <PageHead
        eyebrow={data?.year?.name ?? (data ? t("leave.noYear.short") : t("common.loading"))}
        title={t("leave.title")}
        actions={
          data?.year ? (
            <button type="button" className="btn btn-primary" onClick={() => setApplying(true)} data-testid="apply-leave">
              <Plus size={18} aria-hidden="true" />
              {t("leave.apply")}
            </button>
          ) : null
        }
      />

      <Tabs tabs={tabs} value={tab} onChange={setTab} label={t("leave.title")}>
        {tab === "mine" ? (
          <MyLeavePanel
            mine={mine}
            yearId={yearId}
            onYear={setYearId}
            onApply={() => setApplying(true)}
            onCancel={(request) => setActing({ request, action: "cancel" })}
          />
        ) : tab === "inbox" ? (
          <InboxPanel
            inbox={inbox}
            all={inboxAll}
            onAll={setInboxAll}
            canApproveAll={canApproveAll}
            onAct={(request, action) => setActing({ request, action })}
          />
        ) : (
          <LeaveTypesPanel onChanged={types.reload} />
        )}
      </Tabs>

      {applying ? (
        <ApplyLeaveDialog
          open
          types={types.data ?? []}
          balances={data?.balances ?? []}
          onClose={() => setApplying(false)}
          onApplied={(request) => {
            setApplying(false);
            setTab("mine");
            mine.reload();
            toast(
              request.status === "APPROVED"
                ? t("leave.appliedApproved")
                : request.approverName
                  ? t("leave.applied", { name: request.approverName })
                  : t("leave.appliedSchool"),
            );
          }}
        />
      ) : null}
      {acting ? (
        <LeaveActionDialog
          key={`${acting.request.id}:${acting.action}`}
          request={acting.request}
          action={acting.action}
          onClose={() => setActing(null)}
          onDone={(request) => afterAction(request, acting.action)}
        />
      ) : null}
    </>
  );
}

function MyLeavePanel({
  mine,
  yearId,
  onYear,
  onApply,
  onCancel,
}: {
  mine: ApiData<MyLeave>;
  yearId: string;
  onYear: (id: string) => void;
  onApply: () => void;
  onCancel: (request: StaffLeaveRequest) => void;
}) {
  const { t } = useI18n();
  const data = mine.data;
  if (mine.error && !data) {
    return (
      <section className="card">
        <ErrorState error={mine.error} onRetry={mine.reload} />
      </section>
    );
  }
  if (!data) {
    return (
      <section className="card">
        <LoadingRows rows={4} />
      </section>
    );
  }
  if (!data.year) {
    return (
      <section className="card">
        <p className="empty" data-testid="leave-no-year">
          {t("leave.noYear")}
        </p>
      </section>
    );
  }
  const approver = data.approver;
  return (
    <>
      <section className="card flex flex-col gap-3" aria-labelledby="my-balances">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2 id="my-balances">{t("leave.myBalances")}</h2>
          {data.years.length > 1 ? (
            <label className="field">
              <span className="sr-only">{t("leave.year")}</span>
              <select className="input" name="yearId" value={yearId || data.year.id} onChange={(e) => onYear(e.target.value)}>
                {data.years.map((y) => (
                  <option key={y.id} value={y.id}>
                    {y.name}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
        </div>
        <p className="text-[13.5px] text-ink-2" data-testid="leave-approver">
          {approver.routing === "DEPARTMENT_HEAD" && approver.departmentHead
            ? t("leave.goesTo.head", { name: approver.departmentHead.name })
            : t("leave.goesTo.school")}
        </p>
        <BalanceGrid balances={data.balances} />
      </section>

      <section className="card" aria-labelledby="my-requests">
        <div className="card-head">
          <h2 id="my-requests">{t("leave.myRequests")}</h2>
          <button type="button" className="btn btn-sm" onClick={onApply}>
            <Plus size={16} aria-hidden="true" />
            {t("leave.apply")}
          </button>
        </div>
        <RequestList
          requests={data.requests}
          empty={t("leave.noRequests")}
          testId="my-requests"
          actions={(r) =>
            r.canCancel ? (
              <button type="button" className="btn btn-sm" onClick={() => onCancel(r)}>
                {t("leave.cancel")}
              </button>
            ) : null
          }
        />
      </section>
    </>
  );
}

/** The approver inbox: requests waiting for the caller, oldest first. */
export function InboxPanel({
  inbox,
  all,
  onAll,
  canApproveAll,
  onAct,
}: {
  inbox: ApiData<StaffLeaveRequest[]>;
  all: boolean;
  onAll: (all: boolean) => void;
  canApproveAll: boolean;
  onAct: (request: StaffLeaveRequest, action: LeaveAction) => void;
}) {
  const { t } = useI18n();
  const list = inbox.data ?? [];

  return (
    <section className="card" aria-labelledby="inbox-heading">
      <div className="card-head">
        <h2 id="inbox-heading">{t("leave.inbox.title")}</h2>
        {canApproveAll ? (
          <label className="check">
            <input type="checkbox" name="all" checked={all} onChange={(e) => onAll(e.target.checked)} />
            <span>{t("leave.inbox.all")}</span>
          </label>
        ) : null}
      </div>
      {inbox.error && !inbox.data ? (
        <ErrorState error={inbox.error} onRetry={inbox.reload} />
      ) : !inbox.data ? (
        <LoadingRows rows={3} />
      ) : (
        <RequestList
          requests={list}
          showPerson
          showBalance
          empty={all ? t("leave.inbox.emptyAll") : t("leave.inbox.empty")}
          testId="leave-inbox"
          actions={(r) =>
            r.canDecide ? (
              <>
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  onClick={() => onAct(r, "approve")}
                  aria-label={t("leave.approveFor", { name: r.userName ?? "" })}
                >
                  <Check size={16} aria-hidden="true" />
                  {t("leave.approve")}
                </button>
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => onAct(r, "reject")}
                  aria-label={t("leave.rejectFor", { name: r.userName ?? "" })}
                >
                  <X size={16} aria-hidden="true" />
                  {t("leave.reject")}
                </button>
              </>
            ) : null
          }
        />
      )}
    </section>
  );
}

type TypeOpen = { kind: "edit"; type: LeaveType | null } | { kind: "delete"; type: LeaveType } | null;

/** The school's leave types (staff.manage). */
function LeaveTypesPanel({ onChanged }: { onChanged: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const types = useApiData("leave:types:all", () => leaveApi.types(true));
  const [open, setOpen] = useState<TypeOpen>(null);
  const [adding, setAdding] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const list = types.data ?? [];

  const changed = () => {
    types.reload();
    onChanged();
  };

  const addStandard = async () => {
    setAdding(true);
    setProblem(null);
    try {
      const before = list.length;
      const after = await leaveApi.addStandardTypes();
      changed();
      const added = Math.max(0, after.length - before);
      toast(added > 0 ? plural(t, "leave.types.standardAdded", added) : t("leave.types.standardNone"));
    } catch (caught) {
      setProblem(errorMessage(toApiError(caught), t));
    } finally {
      setAdding(false);
    }
  };

  return (
    <section className="card" aria-labelledby="types-heading">
      <div className="card-head flex-wrap gap-2">
        <h2 id="types-heading">{t("leave.types.title")}</h2>
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn btn-sm" onClick={addStandard} disabled={adding}>
            <ListPlus size={16} aria-hidden="true" />
            {adding ? t("common.working") : t("leave.types.standard")}
          </button>
          <button type="button" className="btn btn-sm btn-primary" onClick={() => setOpen({ kind: "edit", type: null })}>
            <Plus size={16} aria-hidden="true" />
            {t("leave.types.add")}
          </button>
        </div>
      </div>
      <p className="mb-3 text-[13.5px] text-ink-2">{t("leave.types.intro")}</p>
      <FormAlert message={problem} />
      {types.error && !types.data ? (
        <ErrorState error={types.error} onRetry={types.reload} />
      ) : !types.data ? (
        <LoadingRows rows={4} />
      ) : list.length === 0 ? (
        <p className="empty" data-testid="leave-types-empty">
          {t("leave.types.empty")}
        </p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table" data-testid="leave-types">
              <thead>
                <tr>
                  <th scope="col">{t("leave.types.col.name")}</th>
                  <th scope="col" className="r">
                    {t("leave.types.col.quota")}
                  </th>
                  <th scope="col" className="r">
                    {t("leave.types.col.carry")}
                  </th>
                  <th scope="col">{t("leave.types.col.rules")}</th>
                  <th scope="col">
                    <span className="sr-only">{t("common.actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.map((x) => (
                  <tr key={x.id}>
                    <td>
                      <b>{x.name}</b> <span className="mono text-[12.5px] text-ink-3">{x.code}</span>
                    </td>
                    <td className="r num">{x.lossOfPay ? "—" : formatNumber(x.yearlyQuota)}</td>
                    <td className="r num">{x.lossOfPay ? "—" : formatNumber(x.carryForwardCap)}</td>
                    <td>
                      <TypeRules type={x} />
                    </td>
                    <td className="r whitespace-nowrap">
                      <TypeActions type={x} onOpen={setOpen} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="leave-types-cards">
            {list.map((x) => (
              <li key={x.id} className="rowcard flex-wrap">
                <span className="min-w-0 flex-1 basis-[180px]">
                  <b className="block font-semibold">
                    {x.name} <span className="mono text-[12.5px] font-normal text-ink-3">{x.code}</span>
                  </b>
                  {!x.lossOfPay ? (
                    <span className="block text-[13px] text-ink-2">
                      {t("leave.types.card", {
                        quota: formatNumber(x.yearlyQuota),
                        carry: formatNumber(x.carryForwardCap),
                      })}
                    </span>
                  ) : null}
                  <span className="mt-1 block">
                    <TypeRules type={x} />
                  </span>
                </span>
                <span className="flex gap-2">
                  <TypeActions type={x} onOpen={setOpen} />
                </span>
              </li>
            ))}
          </ul>
        </>
      )}

      {open?.kind === "edit" ? (
        <LeaveTypeDialog
          key={open.type?.id ?? "new"}
          open
          type={open.type}
          onClose={() => setOpen(null)}
          onSaved={(saved) => {
            setOpen(null);
            changed();
            toast(t("leave.types.saved", { name: saved.name }));
          }}
        />
      ) : null}
      {open?.kind === "delete" ? (
        <ConfirmDialog
          open
          title={t("leave.types.deleteTitle", { name: open.type.name })}
          body={t("leave.types.deleteBody")}
          confirmLabel={t("common.delete")}
          onConfirm={async () => {
            await leaveApi.deleteType(open.type.id);
            const name = open.type.name;
            setOpen(null);
            changed();
            toast(t("leave.types.deleted", { name }));
          }}
          onClose={() => setOpen(null)}
        />
      ) : null}
    </section>
  );
}

function TypeRules({ type }: { type: LeaveType }) {
  const { t } = useI18n();
  return (
    <span className="flex flex-wrap gap-1">
      {!type.active ? <Pill tone="neutral">{t("leave.types.off")}</Pill> : null}
      {type.lossOfPay ? <Pill tone="warn">{t("leave.types.lossOfPay")}</Pill> : null}
      {type.halfDayAllowed ? <Pill tone="info">{t("leave.types.halfDays")}</Pill> : null}
    </span>
  );
}

function TypeActions({ type, onOpen }: { type: LeaveType; onOpen: (open: TypeOpen) => void }) {
  const { t } = useI18n();
  return (
    <>
      <button
        type="button"
        className="btn btn-sm"
        onClick={() => onOpen({ kind: "edit", type })}
        aria-label={t("leave.types.editTitle", { name: type.name })}
      >
        <Pencil size={16} aria-hidden="true" />
        {t("common.edit")}
      </button>{" "}
      {!type.inUse ? (
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          onClick={() => onOpen({ kind: "delete", type })}
          aria-label={t("leave.types.deleteTitle", { name: type.name })}
        >
          <Trash2 size={16} aria-hidden="true" />
        </button>
      ) : null}
    </>
  );
}
