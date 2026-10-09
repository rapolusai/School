import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { UsersView } from "@/components/views/users-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Users" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.usersRead}>
      <UsersView />
    </RequirePermission>
  );
}
