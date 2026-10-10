"use client";

import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { ChildFeesView } from "@/components/views/fees/child-fees";
import { useI18n } from "@/lib/i18n";
import { ChildSwitcher, useFamilyChildren } from "./family-shared";

/**
 * /app/family/fees: the parent's fees for the chosen child, with the school's existing online
 * payment. A parent with more than one child switches between them here.
 */
export function FamilyFeesView({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  if (children.error && !children.data) {
    return (
      <>
        <PageHead title={t("nav.fees")} />
        <ErrorState error={children.error} onRetry={children.reload} />
      </>
    );
  }
  if (!children.data) {
    return (
      <>
        <PageHead title={t("nav.fees")} />
        <LoadingRows rows={5} />
      </>
    );
  }
  if (!child) {
    return (
      <>
        <PageHead title={t("nav.fees")} />
        <p className="empty">{t("children.empty")}</p>
      </>
    );
  }
  return (
    <>
      <ChildSwitcher list={list} value={child.id} onChange={choose} />
      <ChildFeesView key={child.id} id={child.id} />
    </>
  );
}
