import type { Metadata } from "next";
import { NotFoundView } from "@/components/views/not-found-view";

export const metadata: Metadata = { title: "Page not found" };

export default function NotFound() {
  return <NotFoundView />;
}
