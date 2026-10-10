import { afterEach, describe, expect, it, vi } from "vitest";
import { CHILD_STORAGE_KEY, isFamilyMember, isParent, pickChild, rememberChild, rememberedChild } from "./family";

const person = (roles: string[], platformAdmin = false) => ({ roles, platformAdmin });

describe("isFamilyMember", () => {
  it("is true only when every role is PARENT or STUDENT", () => {
    expect(isFamilyMember(person(["PARENT"]))).toBe(true);
    expect(isFamilyMember(person(["STUDENT"]))).toBe(true);
    expect(isFamilyMember(person(["PARENT", "STUDENT"]))).toBe(true);
    expect(isFamilyMember(person(["TEACHER", "PARENT"]))).toBe(false);
    expect(isFamilyMember(person(["SCHOOL_ADMIN"]))).toBe(false);
    expect(isFamilyMember(person([]))).toBe(false);
    expect(isFamilyMember(person(["PARENT"], true))).toBe(false);
    expect(isFamilyMember(null)).toBe(false);
  });

  it("tells parents from students", () => {
    expect(isParent(person(["PARENT"]))).toBe(true);
    expect(isParent(person(["STUDENT"]))).toBe(false);
  });
});

describe("the remembered child", () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it("keeps only the child's id on this device", () => {
    expect(rememberedChild()).toBeNull();
    rememberChild("st2");
    expect(window.localStorage.getItem(CHILD_STORAGE_KEY)).toBe("st2");
    expect(rememberedChild()).toBe("st2");
  });

  it("works without storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => rememberChild("st2")).not.toThrow();
    expect(rememberedChild()).toBeNull();
  });

  it("falls back to the first child when the remembered one is no longer linked", () => {
    const list = [{ id: "st1" }, { id: "st2" }];
    expect(pickChild(list, "st2")?.id).toBe("st2");
    expect(pickChild(list, "gone")?.id).toBe("st1");
    expect(pickChild(list, null)?.id).toBe("st1");
    expect(pickChild([], "st1")).toBeUndefined();
  });
});
