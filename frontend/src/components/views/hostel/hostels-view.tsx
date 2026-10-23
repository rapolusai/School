"use client";

import { BedDouble, Pencil, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, parseRupees, rupeesInput } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { plural, useI18n } from "@/lib/i18n";
import type { BlockView, HostelDetail, HostelRequest, HostelStaffRef, HostelView, RoomRequest, RoomView } from "@/lib/types";
import { HOSTEL_TYPES, ROOM_TYPES, type HostelType, type RoomType } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { HostelNav, hostelTypeLabel, roomTypeLabel, useCanManageHostel } from "./hostel-shared";

/* /app/hostel: the hostels with their blocks, rooms and beds (docs/api/phase-3-hostel.md, "Set-up"). */

type HostelValues = { name: string; type: HostelType; wardenUserId: string; address: string; active: boolean };
type BlockValues = { name: string; floors: string };
type RoomValues = { floor: string; number: string; capacity: string; type: RoomType; monthlyFee: string; active: boolean };
type BedsValues = { labels: string };

/** Client-side checks mirroring HostelForm on the API. Pure and exported for tests. */
export function hostelProblems(v: HostelValues): Problems {
  const problems: Problems = {};
  if (!v.name.trim()) problems.name = "hostel.v.name";
  return problems;
}

export function blockProblems(v: BlockValues): Problems {
  const problems: Problems = {};
  if (!v.name.trim()) problems.name = "hostel.v.blockName";
  const floors = Number(v.floors);
  if (!Number.isInteger(floors) || floors < 1 || floors > 30) problems.floors = "hostel.v.floors";
  return problems;
}

export function roomProblems(v: RoomValues): Problems {
  const problems: Problems = {};
  if (!v.number.trim()) problems.number = "hostel.v.roomNumber";
  const capacity = Number(v.capacity);
  if (!Number.isInteger(capacity) || capacity < 1 || capacity > 40) problems.capacity = "hostel.v.capacity";
  const floor = Number(v.floor);
  if (!Number.isInteger(floor) || floor < 0 || floor > 30) problems.floor = "hostel.v.floor";
  const fee = v.monthlyFee.trim() === "" ? 0 : parseRupees(v.monthlyFee);
  if (fee === null || fee < 0) problems.monthlyFee = "hostel.v.fee";
  return problems;
}

/** Bed labels, one per line or comma; empty and repeated labels are problems. */
export function bedLabels(text: string): string[] {
  return text
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export function bedsProblems(v: BedsValues): Problems {
  const labels = bedLabels(v.labels);
  if (labels.length === 0) return { labels: "hostel.v.labels" };
  if (new Set(labels.map((l) => l.toLowerCase())).size < labels.length) return { labels: "hostel.v.labelsRepeat" };
  return {};
}

export function HostelsView() {
  const { t } = useI18n();
  const canManage = useCanManageHostel();
  const hostels = useApiData("hostel:hostels", hostelApi.hostels);
  const [editing, setEditing] = useState<HostelView | "new" | null>(null);
  const { toast } = useToast();
  return (
    <>
      <PageHead
        eyebrow={t("nav.hostel")}
        title={t("hostel.hostels.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setEditing("new")} data-testid="hostel-add">
              <Plus size={18} aria-hidden="true" />
              {t("hostel.hostels.add")}
            </button>
          ) : null
        }
      />
      <HostelNav />
      {hostels.error && !hostels.data ? (
        <ErrorState error={hostels.error} onRetry={hostels.reload} />
      ) : !hostels.data ? (
        <LoadingRows rows={3} />
      ) : hostels.data.length === 0 ? (
        <p className="empty" data-testid="hostels-empty">
          {canManage ? t("hostel.hostels.emptyManage") : t("hostel.hostels.empty")}
        </p>
      ) : (
        <ul className="grid grid-cols-1 gap-3.5 md:grid-cols-2 xl:grid-cols-3" data-testid="hostel-list">
          {hostels.data.map((h) => (
            <li key={h.id} className="card flex flex-col gap-2" data-testid="hostel-card">
              <div className="card-head" style={{ marginBottom: 0 }}>
                <h2 className="flex items-center gap-2">
                  <BedDouble size={18} className="text-ink-3" aria-hidden="true" />
                  {h.name}
                </h2>
                <Pill tone={h.active ? "info" : "neutral"}>{hostelTypeLabel(t, h.type)}</Pill>
              </div>
              <p className="text-[13px] text-ink-2">
                {h.wardenName ? t("hostel.hostels.warden", { name: h.wardenName }) : t("hostel.hostels.noWarden")}
              </p>
              <p className="text-[13px] text-ink-3">
                {t("hostel.hostels.summary", { blocks: h.blocks, rooms: h.rooms, beds: h.beds })} ·{" "}
                {t("hostel.hostels.occupancy", { occupied: h.occupied, beds: h.beds, percent: h.occupancyPercent })}
              </p>
              <div className="mt-auto flex flex-wrap gap-2">
                <Link href={`/app/hostel/occupancy?hostel=${encodeURIComponent(h.id)}`} className="btn btn-sm">
                  {t("hostel.nav.occupancy")}
                </Link>
                <Link href={`/app/hostel/${encodeURIComponent(h.id)}`} className="btn btn-sm" data-testid="hostel-open">
                  {t("hostel.hostels.rooms")}
                </Link>
                {h.canManage ? (
                  <button type="button" className="iconbtn ml-auto" aria-label={t("common.edit")} onClick={() => setEditing(h)}>
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}
      <HostelDialog
        key={editing === "new" ? "new" : (editing?.id ?? "closed")}
        open={editing !== null}
        hostel={editing === "new" ? null : editing}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          toast(t("hostel.hostels.saved"));
          hostels.reload();
        }}
      />
    </>
  );
}

function HostelDialog({ open, hostel, onClose, onSaved }: { open: boolean; hostel: HostelView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const staff = useApiData(open ? "hostel:staff" : null, hostelApi.staff);
  const form = useForm<HostelValues>({
    name: hostel?.name ?? "",
    type: hostel?.type ?? "BOYS",
    wardenUserId: hostel?.wardenUserId ?? "",
    address: hostel?.address ?? "",
    active: hostel?.active ?? true,
  });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(hostelProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: HostelRequest = {
      name: v.name.trim(),
      type: v.type,
      wardenUserId: v.wardenUserId || null,
      address: v.address.trim() || null,
      active: v.active,
    };
    const ok = await form.submit(t, async () => {
      if (hostel) await hostelApi.updateHostel(hostel.id, body);
      else await hostelApi.createHostel(body);
    });
    if (ok) onSaved();
  };
  const wardens: HostelStaffRef[] = staff.data ?? [];
  return (
    <Dialog open={open} onClose={onClose} title={hostel ? t("hostel.hostels.edit") : t("hostel.hostels.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="hostel-form">
        <FormAlert message={form.formError} />
        <TextField label={t("hostel.field.name")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={120} required />
        <SelectField
          label={t("hostel.field.type")}
          name="type"
          value={form.values.type}
          onChange={(e) => form.set("type", e.target.value as HostelType)}
          options={HOSTEL_TYPES.map((type) => ({ value: type, label: hostelTypeLabel(t, type) }))}
        />
        <SelectField
          label={t("hostel.field.warden")}
          name="wardenUserId"
          value={form.values.wardenUserId}
          onChange={(e) => form.set("wardenUserId", e.target.value)}
          error={form.errors.wardenUserId}
          hint={t("hostel.field.wardenHint")}
          options={[{ value: "", label: t("hostel.field.noWarden") }, ...wardens.map((s) => ({ value: s.id, label: s.designation ? `${s.name} · ${s.designation}` : s.name }))]}
        />
        <TextAreaField label={t("hostel.field.address")} name="address" value={form.values.address} onChange={(e) => form.set("address", e.target.value)} rows={2} maxLength={300} />
        {hostel ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            {t("hostel.field.active")}
          </label>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="hostel-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ one hostel: blocks, rooms and beds */

type Open =
  | { kind: "block"; block: BlockView | null }
  | { kind: "removeBlock"; block: BlockView }
  | { kind: "room"; room: RoomView | null; blockId: string }
  | { kind: "removeRoom"; room: RoomView }
  | { kind: "beds"; room: RoomView }
  | null;

/** /app/hostel/[id]: a hostel's blocks and rooms, where beds are added. */
export function HostelDetailView({ id }: { id: string }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const detail = useApiData(`hostel:detail:${id}`, () => hostelApi.hostel(id));
  const [open, setOpen] = useState<Open>(null);
  const d = detail.data;
  const saved = (message: string) => {
    setOpen(null);
    toast(message);
    detail.reload();
  };
  return (
    <>
      <PageHead
        eyebrow={
          <Link href="/app/hostel" className="link">
            {t("nav.hostel")}
          </Link>
        }
        title={d ? d.hostel.name : t("hostel.hostels.title")}
        actions={
          d?.hostel.canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setOpen({ kind: "block", block: null })} data-testid="block-add">
              <Plus size={18} aria-hidden="true" />
              {t("hostel.blocks.add")}
            </button>
          ) : null
        }
      />
      <HostelNav />
      {detail.error && !d ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !d ? (
        <LoadingRows rows={4} />
      ) : d.blocks.length === 0 ? (
        <p className="empty" data-testid="blocks-empty">
          {t("hostel.blocks.empty")}
        </p>
      ) : (
        d.blocks.map((block) => (
          <BlockSection key={block.id} detail={d} block={block} onOpen={setOpen} />
        ))
      )}
      {d ? (
        <>
          <BlockDialog
            key={open?.kind === "block" ? (open.block?.id ?? "new") : "closed"}
            open={open?.kind === "block"}
            hostelId={id}
            block={open?.kind === "block" ? open.block : null}
            onClose={() => setOpen(null)}
            onSaved={() => saved(t("hostel.blocks.saved"))}
          />
          <RoomDialog
            key={open?.kind === "room" ? `${open.blockId}:${open.room?.id ?? "new"}` : "closed"}
            open={open?.kind === "room"}
            hostelId={id}
            blockId={open?.kind === "room" ? open.blockId : ""}
            blocks={d.blocks}
            room={open?.kind === "room" ? open.room : null}
            onClose={() => setOpen(null)}
            onSaved={() => saved(t("hostel.rooms.saved"))}
          />
          <BedsDialog
            key={open?.kind === "beds" ? open.room.id : "closed"}
            open={open?.kind === "beds"}
            hostelId={id}
            room={open?.kind === "beds" ? open.room : null}
            onClose={() => setOpen(null)}
            onSaved={() => saved(t("hostel.beds.saved"))}
          />
          <ConfirmDialog
            open={open?.kind === "removeBlock"}
            title={t("hostel.blocks.remove")}
            body={open?.kind === "removeBlock" ? t("hostel.blocks.removeBody", { name: open.block.name }) : ""}
            confirmLabel={t("common.delete")}
            onConfirm={async () => {
              if (open?.kind !== "removeBlock") return;
              await hostelApi.removeBlock(id, open.block.id);
              saved(t("hostel.blocks.removed"));
            }}
            onClose={() => setOpen(null)}
          />
          <ConfirmDialog
            open={open?.kind === "removeRoom"}
            title={t("hostel.rooms.remove")}
            body={open?.kind === "removeRoom" ? t("hostel.rooms.removeBody", { number: open.room.number }) : ""}
            confirmLabel={t("common.delete")}
            onConfirm={async () => {
              if (open?.kind !== "removeRoom") return;
              await hostelApi.removeRoom(id, open.room.id);
              saved(t("hostel.rooms.removed"));
            }}
            onClose={() => setOpen(null)}
          />
        </>
      ) : null}
    </>
  );
}

function BlockSection({ detail, block, onOpen }: { detail: HostelDetail; block: BlockView; onOpen: (o: Open) => void }) {
  const { t } = useI18n();
  const canManage = detail.hostel.canManage;
  const rooms = detail.rooms.filter((r) => r.blockId === block.id);
  return (
    <section className="card flex flex-col gap-3" aria-label={block.name} data-testid="block-section">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <div className="min-w-0">
          <h2>{block.name}</h2>
          <p className="text-[13px] text-ink-3">
            {plural(t, "hostel.blocks.floors", block.floors)} · {plural(t, "hostel.blocks.rooms", rooms.length)}
          </p>
        </div>
        {canManage ? (
          <div className="flex gap-1">
            <button type="button" className="btn btn-sm" onClick={() => onOpen({ kind: "room", room: null, blockId: block.id })} data-testid="room-add">
              <Plus size={16} aria-hidden="true" />
              {t("hostel.rooms.add")}
            </button>
            <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => onOpen({ kind: "block", block })}>
              <Pencil size={16} aria-hidden="true" />
            </button>
            <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => onOpen({ kind: "removeBlock", block })}>
              <Trash2 size={16} aria-hidden="true" />
            </button>
          </div>
        ) : null}
      </div>
      {rooms.length === 0 ? (
        <p className="text-[13.5px] text-ink-3">{t("hostel.rooms.empty")}</p>
      ) : (
        <div className="table-wrap">
          <table className="table" data-testid="rooms-table">
            <thead>
              <tr>
                <th scope="col">{t("hostel.field.roomNumber")}</th>
                <th scope="col">{t("hostel.field.floor")}</th>
                <th scope="col">{t("hostel.field.roomType")}</th>
                <th scope="col" className="r">
                  {t("hostel.field.capacity")}
                </th>
                <th scope="col" className="r">
                  {t("hostel.field.beds")}
                </th>
                <th scope="col" className="r">
                  {t("hostel.field.monthlyFee")}
                </th>
                <th scope="col">{t("hostel.field.status")}</th>
                {canManage ? <th scope="col" className="r"><span className="sr-only">{t("common.actions")}</span></th> : null}
              </tr>
            </thead>
            <tbody>
              {rooms.map((room) => (
                <tr key={room.id} data-testid="room-row">
                  <td className="font-semibold">{room.number}</td>
                  <td>{room.floor}</td>
                  <td>{roomTypeLabel(t, room.type)}</td>
                  <td className="r num">{room.capacity}</td>
                  <td className="r num" data-testid="room-beds">
                    {t("hostel.rooms.bedsOf", { occupied: room.occupied, beds: room.beds })}
                  </td>
                  <td className="r num">{formatPaise(room.monthlyFeePaise)}</td>
                  <td>
                    <Pill tone={room.active ? "good" : "neutral"}>{room.active ? t("hostel.rooms.inUse") : t("hostel.rooms.notInUse")}</Pill>
                  </td>
                  {canManage ? (
                    <td className="r">
                      <span className="inline-flex gap-1">
                        <button type="button" className="btn btn-sm" onClick={() => onOpen({ kind: "beds", room })} data-testid="beds-add">
                          {t("hostel.beds.add")}
                        </button>
                        <button type="button" className="iconbtn" aria-label={t("common.edit")} onClick={() => onOpen({ kind: "room", room, blockId: block.id })}>
                          <Pencil size={16} aria-hidden="true" />
                        </button>
                        <button type="button" className="iconbtn" aria-label={t("common.delete")} onClick={() => onOpen({ kind: "removeRoom", room })}>
                          <Trash2 size={16} aria-hidden="true" />
                        </button>
                      </span>
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function BlockDialog({ open, hostelId, block, onClose, onSaved }: { open: boolean; hostelId: string; block: BlockView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<BlockValues>({ name: block?.name ?? "", floors: String(block?.floors ?? 1) });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(blockProblems(form.values), t, formEl)) return;
    const body = { name: form.values.name.trim(), floors: Number(form.values.floors) };
    const ok = await form.submit(t, async () => {
      if (block) await hostelApi.updateBlock(hostelId, block.id, body);
      else await hostelApi.addBlock(hostelId, body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open={open} onClose={onClose} title={block ? t("hostel.blocks.edit") : t("hostel.blocks.add")} closeLabel={t("common.close")}>
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="block-form">
        <FormAlert message={form.formError} />
        <TextField label={t("hostel.field.blockName")} name="name" value={form.values.name} onChange={(e) => form.set("name", e.target.value)} error={form.errors.name} maxLength={80} required />
        <TextField label={t("hostel.field.floors")} name="floors" type="number" inputMode="numeric" min={1} max={30} value={form.values.floors} onChange={(e) => form.set("floors", e.target.value)} error={form.errors.floors} required />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="block-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function RoomDialog({
  open,
  hostelId,
  blockId,
  blocks,
  room,
  onClose,
  onSaved,
}: {
  open: boolean;
  hostelId: string;
  blockId: string;
  blocks: BlockView[];
  room: RoomView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const form = useForm<RoomValues>({
    floor: String(room?.floor ?? 0),
    number: room?.number ?? "",
    capacity: String(room?.capacity ?? 2),
    type: room?.type ?? "DOUBLE",
    monthlyFee: room ? rupeesInput(room.monthlyFeePaise) : "",
    active: room?.active ?? true,
  });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const block = blocks.find((b) => b.id === blockId);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.check(roomProblems(form.values), t, formEl)) return;
    const v = form.values;
    const body: RoomRequest = {
      floor: Number(v.floor),
      number: v.number.trim(),
      capacity: Number(v.capacity),
      type: v.type,
      monthlyFeePaise: v.monthlyFee.trim() === "" ? 0 : (parseRupees(v.monthlyFee) ?? 0),
      active: v.active,
    };
    const ok = await form.submit(t, async () => {
      if (room) await hostelApi.updateRoom(hostelId, room.id, body);
      else await hostelApi.addRoom(hostelId, blockId, body);
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={room ? t("hostel.rooms.edit") : t("hostel.rooms.add")}
      description={block ? t("hostel.rooms.inBlock", { block: block.name }) : undefined}
      closeLabel={t("common.close")}
    >
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="room-form">
        <FormAlert message={form.formError} />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t("hostel.field.roomNumber")} name="number" value={form.values.number} onChange={(e) => form.set("number", e.target.value)} error={form.errors.number} maxLength={20} required />
          <TextField label={t("hostel.field.floor")} name="floor" type="number" inputMode="numeric" min={0} max={30} value={form.values.floor} onChange={(e) => form.set("floor", e.target.value)} error={form.errors.floor} />
          <SelectField
            label={t("hostel.field.roomType")}
            name="type"
            value={form.values.type}
            onChange={(e) => form.set("type", e.target.value as RoomType)}
            options={ROOM_TYPES.map((type) => ({ value: type, label: roomTypeLabel(t, type) }))}
          />
          <TextField label={t("hostel.field.capacity")} name="capacity" type="number" inputMode="numeric" min={1} max={40} value={form.values.capacity} onChange={(e) => form.set("capacity", e.target.value)} error={form.errors.capacity} required />
        </div>
        <TextField
          label={t("hostel.field.monthlyFee")}
          name="monthlyFee"
          inputMode="decimal"
          value={form.values.monthlyFee}
          onChange={(e) => form.set("monthlyFee", e.target.value)}
          error={form.errors.monthlyFee}
          hint={t("hostel.field.monthlyFeeHint")}
        />
        {room ? (
          <label className="check">
            <input type="checkbox" name="active" checked={form.values.active} onChange={(e) => form.set("active", e.target.checked)} />
            {t("hostel.rooms.inUse")}
          </label>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="room-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function BedsDialog({ open, hostelId, room, onClose, onSaved }: { open: boolean; hostelId: string; room: RoomView | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const form = useForm<BedsValues>({ labels: "" });
  const [formEl, setFormEl] = useState<HTMLFormElement | null>(null);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!room) return;
    if (!form.check(bedsProblems(form.values), t, formEl)) return;
    const ok = await form.submit(t, async () => {
      await hostelApi.addBeds(hostelId, room.id, { labels: bedLabels(form.values.labels) });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t("hostel.beds.add")}
      description={room ? t("hostel.beds.intro", { number: room.number, free: Math.max(room.capacity - room.beds, 0) }) : undefined}
      closeLabel={t("common.close")}
    >
      <form ref={setFormEl} method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="beds-form">
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("hostel.field.bedLabels")}
          name="labels"
          value={form.values.labels}
          onChange={(e) => form.set("labels", e.target.value)}
          error={form.errors.labels}
          hint={t("hostel.field.bedLabelsHint")}
          rows={3}
          required
        />
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="beds-save">
            {t("common.save")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
