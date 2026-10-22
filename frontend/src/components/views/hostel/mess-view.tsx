"use client";

import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { hostelApi } from "@/lib/hostel-api";
import { useI18n } from "@/lib/i18n";
import { MEALS, type Meal, type MenuSlot, type WeeklyMenu } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { HostelNav, HostelPicker, mealLabel, useChosenHostel, useLocale, weekdayLabel } from "./hostel-shared";

/* /app/hostel/mess: the week's menu per hostel and the warden's daily head count per meal. */

const DAYS = [1, 2, 3, 4, 5, 6, 7] as const;

type MenuValues = Record<string, { dish: string; veg: boolean }>;

const slotKey = (day: number, meal: Meal) => `${day}:${meal}`;

/** The form's values from the saved menu: every slot of the week, blank where nothing is planned. */
export function menuValues(items: MenuSlot[]): MenuValues {
  const values: MenuValues = {};
  for (const day of DAYS) for (const meal of MEALS) values[slotKey(day, meal)] = { dish: "", veg: true };
  for (const item of items) values[slotKey(item.dayOfWeek, item.meal)] = { dish: item.dish, veg: item.veg };
  return values;
}

export function MessView() {
  const { t } = useI18n();
  const { hostels, list, hostel, choose } = useChosenHostel();
  return (
    <>
      <PageHead eyebrow={t("nav.hostel")} title={t("hostel.mess.title")} />
      <HostelNav />
      {hostels.error && !hostels.data ? (
        <ErrorState error={hostels.error} onRetry={hostels.reload} />
      ) : !hostels.data ? (
        <LoadingRows rows={3} />
      ) : !hostel ? (
        <p className="empty">{t("hostel.hostels.empty")}</p>
      ) : (
        <>
          <HostelPicker list={list} value={hostel.id} onChange={choose} />
          <div className="grid grid-cols-1 gap-3.5 xl:grid-cols-[2fr_1fr]">
            <MenuCard key={`menu:${hostel.id}`} hostelId={hostel.id} />
            <AttendanceCard key={`mess:${hostel.id}`} hostelId={hostel.id} canWarden={hostel.canWarden} />
          </div>
        </>
      )}
    </>
  );
}

function MenuCard({ hostelId }: { hostelId: string }) {
  const menu = useApiData(`hostel:menu:${hostelId}`, () => hostelApi.menu(hostelId));
  const data = menu.data;
  if (menu.error && !data) return <ErrorState error={menu.error} onRetry={menu.reload} />;
  if (!data) return <LoadingRows rows={4} />;
  return <MenuForm key={data.items.map((i) => `${i.dayOfWeek}${i.meal}${i.dish}${i.veg}`).join("|")} menu={data} onSaved={menu.reload} />;
}

function MenuForm({ menu, onSaved }: { menu: WeeklyMenu; onSaved: () => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const form = useForm<MenuValues>(menuValues(menu.items));
  const [editing, setEditing] = useState(false);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const items = Object.entries(form.values).map(([key, v]) => {
      const [day, meal] = key.split(":");
      return { dayOfWeek: Number(day), meal: meal as Meal, dish: v.dish.trim(), veg: v.veg };
    });
    const ok = await form.submit(t, async () => {
      await hostelApi.saveMenu(menu.hostelId, { items });
    });
    if (ok) {
      setEditing(false);
      toast(t("hostel.menu.saved"));
      onSaved();
    }
  };
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="hostel-menu" data-testid="hostel-menu">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="hostel-menu">{t("hostel.menu.title")}</h2>
        {menu.canEdit && !editing ? (
          <button type="button" className="btn btn-sm" onClick={() => setEditing(true)} data-testid="menu-edit">
            {t("common.edit")}
          </button>
        ) : null}
      </div>
      <form method="post" noValidate onSubmit={submit} className="flex flex-col gap-3">
        <FormAlert message={form.formError} />
        <div className="table-wrap">
          <table className="table" data-testid="menu-table">
            <thead>
              <tr>
                <th scope="col">{t("hostel.menu.day")}</th>
                {MEALS.map((meal) => (
                  <th key={meal} scope="col">
                    {mealLabel(t, meal)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {DAYS.map((day) => (
                <tr key={day} data-testid="menu-day">
                  <th scope="row">{weekdayLabel(t, day)}</th>
                  {MEALS.map((meal) => {
                    const key = slotKey(day, meal);
                    const slot = form.values[key];
                    return (
                      <td key={meal} className="align-top">
                        {editing ? (
                          <span className="flex flex-col gap-1">
                            <input
                              className="input"
                              name={`dish-${key}`}
                              aria-label={`${weekdayLabel(t, day)} ${mealLabel(t, meal)}`}
                              value={slot.dish}
                              maxLength={200}
                              onChange={(e) => form.set(key, { ...slot, dish: e.target.value })}
                            />
                            <label className="check text-[12.5px]">
                              <input type="checkbox" name={`veg-${key}`} checked={slot.veg} onChange={(e) => form.set(key, { ...slot, veg: e.target.checked })} />
                              {t("hostel.menu.veg")}
                            </label>
                          </span>
                        ) : slot.dish ? (
                          <>
                            {slot.dish}
                            <span className={`block text-[11.5px] ${slot.veg ? "text-good" : "text-warn"}`}>{slot.veg ? t("hostel.menu.veg") : t("hostel.menu.nonVeg")}</span>
                          </>
                        ) : (
                          <span className="text-ink-3">—</span>
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {editing ? (
          <div className="flex flex-wrap justify-end gap-2">
            <button
              type="button"
              className="btn"
              onClick={() => {
                form.reset(menuValues(menu.items));
                setEditing(false);
              }}
            >
              {t("common.cancel")}
            </button>
            <button type="submit" className="btn btn-primary" disabled={form.submitting} data-testid="menu-save">
              {t("common.save")}
            </button>
          </div>
        ) : null}
      </form>
    </section>
  );
}

type CountValues = Record<Meal, string>;

function AttendanceCard({ hostelId, canWarden }: { hostelId: string; canWarden: boolean }) {
  const { t } = useI18n();
  const locale = useLocale();
  const { toast } = useToast();
  const [date, setDate] = useState(() => todayInIndia());
  const day = useApiData(`hostel:mess:${hostelId}:${date}`, () => hostelApi.messDay(hostelId, date));
  const data = day.data;
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="mess-attendance" data-testid="mess-attendance">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="mess-attendance">{t("hostel.mess.attendance")}</h2>
      </div>
      <label className="field">
        <span className="field-label">{t("reports.col.date")}</span>
        <input type="date" className="input" name="date" value={date} max={todayInIndia()} onChange={(e) => setDate(e.target.value)} />
      </label>
      {day.error && !data ? (
        <ErrorState error={day.error} onRetry={day.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : (
        <CountForm
          key={`${date}:${data.counts.map((c) => `${c.meal}${c.headCount}`).join("|")}`}
          hostelId={hostelId}
          date={date}
          boarders={data.boarders}
          counts={Object.fromEntries(MEALS.map((meal) => [meal, String(data.counts.find((c) => c.meal === meal)?.headCount ?? "")])) as CountValues}
          markedBy={data.counts.find((c) => c.markedByName)?.markedByName ?? null}
          canWarden={canWarden}
          onSaved={() => {
            toast(t("hostel.mess.saved", { date: formatPlainDate(date, locale) }));
            day.reload();
          }}
        />
      )}
    </section>
  );
}

function CountForm({
  hostelId,
  date,
  boarders,
  counts,
  markedBy,
  canWarden,
  onSaved,
}: {
  hostelId: string;
  date: string;
  boarders: number;
  counts: CountValues;
  markedBy: string | null;
  canWarden: boolean;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const form = useForm<CountValues>(counts);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const entries = MEALS.filter((meal) => form.values[meal].trim() !== "").map((meal) => ({ meal, headCount: Number(form.values[meal]) }));
    if (entries.length === 0 || entries.some((e) => !Number.isInteger(e.headCount) || e.headCount < 0 || e.headCount > 5000)) {
      form.setFormError(t("hostel.v.headCount"));
      return;
    }
    const ok = await form.submit(t, async () => {
      await hostelApi.markMess(hostelId, date, { counts: entries });
    });
    if (ok) onSaved();
  };
  return (
    <form method="post" noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="mess-form">
      <p className="text-[13px] text-ink-3">{t("hostel.mess.boarders", { count: boarders })}</p>
      <FormAlert message={form.formError} />
      {MEALS.map((meal) => (
        <label key={meal} className="field">
          <span className="field-label">{mealLabel(t, meal)}</span>
          <input
            type="number"
            className="input"
            name={meal}
            inputMode="numeric"
            min={0}
            max={5000}
            value={form.values[meal]}
            readOnly={!canWarden}
            onChange={(e) => form.set(meal, e.target.value)}
          />
        </label>
      ))}
      {markedBy ? <p className="text-[12.5px] text-ink-3">{t("hostel.mess.markedBy", { name: markedBy })}</p> : null}
      {canWarden ? (
        <button type="submit" className="btn btn-primary self-end" disabled={form.submitting} data-testid="mess-save">
          {t("common.save")}
        </button>
      ) : null}
    </form>
  );
}
