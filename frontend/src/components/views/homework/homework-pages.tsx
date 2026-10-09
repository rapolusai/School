"use client";

import { AccessDenied } from "@/components/access";
import { useAuth } from "@/lib/auth";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { Me } from "@/lib/types";
import { HomeworkDetailView } from "./homework-detail-view";
import { HomeworkListView } from "./homework-list-view";
import { ChildHomeworkDetailView, ParentHomeworkView, StudentHomeworkDetailView, StudentHomeworkView } from "./learner-homework";

/** Which homework screen a person gets: staff who set homework, a student, or a parent. Pure. */
export function homeworkAudience(me: Me | null | undefined): "staff" | "student" | "parent" | null {
  if (hasPermission(me, PERMISSIONS.homeworkManage)) return "staff";
  if (me?.roles.includes("STUDENT")) return "student";
  if (hasPermission(me, PERMISSIONS.childView)) return "parent";
  return null;
}

/** /app/homework for whoever opens it. */
export function HomeworkPage({ initialChildId }: { initialChildId?: string }) {
  const { me } = useAuth();
  const audience = homeworkAudience(me);
  if (audience === "staff") return <HomeworkListView />;
  if (audience === "student") return <StudentHomeworkView />;
  if (audience === "parent") return <ParentHomeworkView initialChildId={initialChildId} />;
  return <AccessDenied />;
}

/** /app/homework/[id]: the tracker for staff, the answer form for a student, read only for a parent. */
export function HomeworkDetailPage({ id, childId }: { id: string; childId?: string }) {
  const { me } = useAuth();
  const audience = homeworkAudience(me);
  if (audience === "staff") return <HomeworkDetailView id={id} />;
  if (audience === "student") return <StudentHomeworkDetailView id={id} />;
  if (audience === "parent" && childId) return <ChildHomeworkDetailView studentId={childId} id={id} />;
  if (audience === "parent") return <ParentHomeworkView />;
  return <AccessDenied />;
}
