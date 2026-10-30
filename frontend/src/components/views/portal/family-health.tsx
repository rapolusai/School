"use client";

import { HeartPulse } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { AllergyBadges, badgesOf, OutcomePill, when } from "@/components/views/health/health-shared";
import { VisitLine } from "@/components/views/health/infirmary-view";
import { ConsentCard, ItemDialog, ItemList } from "@/components/views/health/medical-record-view";
import { todayInIndia } from "@/lib/format";
import { healthApi } from "@/lib/health-api";
import { localeFor, useI18n } from "@/lib/i18n";
import type { HealthItem, InfirmaryVisit, ItemKind, MedicalRecord } from "@/lib/types";
import { useApiData, type ApiData } from "@/lib/use-api-data";
import { ChildSwitcher, useFamilyChildren } from "./family-shared";

/** What a parent may keep themselves: allergies and conditions. Medication and vaccinations are the school's. */
const PARENT_KINDS: readonly ItemKind[] = ["ALLERGY", "CONDITION"];

/** /app/family/health: the parent keeps the child's allergies, conditions and consent and sees infirmary visits. */
export function FamilyHealthView({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  return (
    <>
      <PageHead title={t("nav.health")} />
      {children.error && !children.data ? (
        <ErrorState error={children.error} onRetry={children.reload} />
      ) : !children.data ? (
        <LoadingRows rows={3} />
      ) : list.length === 0 ? (
        <p className="empty">{t("children.notLinked")}</p>
      ) : (
        <>
          <ChildSwitcher list={list} value={child?.id} onChange={choose} />
          {child ? <ChildHealth key={child.id} childId={child.id} /> : null}
        </>
      )}
    </>
  );
}

function ChildHealth({ childId }: { childId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { toast } = useToast();
  const record = useApiData(`health:child:${childId}`, () => healthApi.childHealth(childId));
  const [item, setItem] = useState<{ kind: ItemKind; item: HealthItem | null } | null>(null);
  const [removing, setRemoving] = useState<HealthItem | null>(null);
  const r = record.data;
  if (record.error && !r) return <ErrorState error={record.error} onRetry={record.reload} />;
  if (!r) return <LoadingRows rows={4} />;
  const saved = () => {
    toast(t("health.family.saved"));
    record.reload();
  };
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2" data-testid="family-health">
      <section className="card lg:col-span-2">
        <p className="text-[13.5px] text-ink-2">{t("health.family.intro")}</p>
        <AllergyBadges badges={badgesOf(r.studentId, r.allergies)} />
        <dl className="kv mt-2">
          <div>
            <dt>{t("health.record.bloodGroup")}</dt>
            <dd>{r.bloodGroup ?? t("health.record.unknown")}</dd>
          </div>
          <div>
            <dt>{t("health.record.emergency")}</dt>
            <dd>{r.emergencyContact ? `${r.emergencyContact.name} · ${r.emergencyContact.phoneMasked}` : "—"}</dd>
          </div>
        </dl>
      </section>
      <ItemList kind="ALLERGY" items={r.allergies} canEdit onAdd={() => setItem({ kind: "ALLERGY", item: null })} onEdit={(i) => (i.source === "PARENT" ? setItem({ kind: "ALLERGY", item: i }) : undefined)} onRemove={(i) => (i.source === "PARENT" ? setRemoving(i) : undefined)} />
      <ItemList kind="CONDITION" items={r.conditions} canEdit onAdd={() => setItem({ kind: "CONDITION", item: null })} onEdit={(i) => (i.source === "PARENT" ? setItem({ kind: "CONDITION", item: i }) : undefined)} onRemove={(i) => (i.source === "PARENT" ? setRemoving(i) : undefined)} />
      <ConsentCard record={r} canEdit onSave={async (firstAid, hospital) => { await healthApi.childConsent(childId, { firstAid, hospital }); saved(); }} />
      <ItemList kind="MEDICATION" items={r.medications} canEdit={false} />
      <ItemList kind="VACCINATION" items={r.vaccinations} canEdit={false} />
      <section className="card" data-testid="family-visits">
        <div className="card-head">
          <h2>{t("health.record.visits")}</h2>
        </div>
        {r.visits.length === 0 ? (
          <p className="empty">{t("health.record.noVisits")}</p>
        ) : (
          <ul className="rowcards">
            {r.visits.map((v) => (
              <VisitLine key={v.id} visit={v} locale={locale} />
            ))}
          </ul>
        )}
      </section>
      <ItemDialog
        key={item ? `${item.kind}:${item.item?.id ?? "new"}` : "no-item"}
        item={item?.item ?? null}
        kinds={item ? [item.kind] : PARENT_KINDS}
        open={item !== null}
        onClose={() => setItem(null)}
        onSave={async (body) => {
          if (item?.item) await healthApi.childUpdateItem(childId, item.item.id, body);
          else await healthApi.childAddItem(childId, body);
          saved();
        }}
      />
      <ConfirmDialog
        open={removing !== null}
        title={t("health.item.removeTitle")}
        body={removing ? t("health.item.removeBody", { name: removing.name }) : ""}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          if (!removing) return;
          await healthApi.childRemoveItem(childId, removing.id);
          setRemoving(null);
          saved();
        }}
        onClose={() => setRemoving(null)}
      />
    </div>
  );
}

/** Today's infirmary visit of a child, if any. Pure; exported for tests. */
export function visitToday(record: MedicalRecord | undefined, today: string): InfirmaryVisit | undefined {
  // Visit times are UTC instants; "today" is the school's date in India, so compare India dates.
  const day = (instant: string) => todayInIndia(new Date(instant));
  return record?.visits.find((v) => day(v.timeIn) === today || (v.timeOut !== null && day(v.timeOut) === today));
}

/** The family home card: shown only when the child was in the infirmary today. */
export function FamilyHealthCard({ data, today, href }: { data: ApiData<MedicalRecord>; today: string; href: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const visit = visitToday(data.data, today);
  if (!visit) return null;
  return (
    <section className="card flex flex-col gap-2" aria-labelledby="family-health-card" data-testid="family-health-card">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="family-health-card">{t("health.family.todayTitle")}</h2>
        <HeartPulse size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      <p className="flex flex-wrap items-center gap-2 text-[13.5px]">
        <span className="font-semibold">{t("health.family.todayLine", { time: when(visit.timeIn, locale), complaint: visit.complaint })}</span>
        <OutcomePill outcome={visit.outcome} />
      </p>
      {visit.treatment ? <p className="text-[13px] text-ink-2">{visit.treatment}</p> : null}
      <Link href={href} className="link text-[13.5px]">
        {t("health.family.open")}
      </Link>
    </section>
  );
}
