import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

/** Today in India as "YYYY-MM-DD", moved by `days`. */
function indiaDate(days = 0): string {
  return new Date(Date.now() + 330 * 60_000 + days * 86_400_000).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today. */
function academicYear() {
  const today = indiaDate();
  const year = Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

type Auth = { headers: { Authorization: string } };

function client(request: APIRequestContext, auth: Auth) {
  return async <T = { id: string }>(path: string, data?: unknown, status = 201): Promise<T> => {
    const res = await request.post(path, { ...auth, data });
    expect(res.status(), `POST ${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };
}

/** Where the two stops are; the attendant's phone reports the first one. */
const GATE_STOP = { latitude: 17.4401, longitude: 78.3489 };
const MARKET_STOP = { latitude: 17.4483, longitude: 78.3915 };
const ROUTE = "Route 1 – Lake View";
const BUS = "TS09 UB 4410";

/**
 * Through the API: a current year, Class 5 A with Arjun Sharma in it, his mother's sign-in, alerts that are not held
 * for quiet hours (so the run does not depend on the time of day), and the transport set-up the admin builds a route
 * from: a bus, a driver and an attendant (members of staff with the Bus attendant role) and a fee zone.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const post = client(request, auth);
  const attendant: Credentials = { schoolCode, email: `anita@${schoolCode}.example.com`, password: PASSWORD };
  const { id: attendantUser } = await post("/api/users", {
    name: "Anita Rao",
    email: attendant.email,
    password: PASSWORD,
    roles: ["BUS_ATTENDANT"],
  });
  const { id: driverUser } = await post("/api/users", {
    name: "Dev Kumar",
    email: `dev@${schoolCode}.example.com`,
    password: PASSWORD,
    roles: ["BUS_ATTENDANT"],
  });

  const year = academicYear();
  await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40 });
  const arjun = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo: "E2E/BUS/1",
    firstName: "Arjun",
    lastName: "Sharma",
    dateOfBirth: "2016-03-12",
    gender: "MALE",
    admissionDate: year.startsOn,
    sectionId: section5A,
    rollNo: 1,
    guardians: [{ name: "Neha Sharma", relation: "MOTHER", phone: "98765 40001", primary: true }],
  });
  const parent: Credentials = { schoolCode, email: `neha@${schoolCode}.example.com`, password: "Parent-temp-2026" };
  await post(`/api/students/${arjun.id}/guardians/${arjun.guardians[0].id}/sign-in`, { mode: "CREATE", email: parent.email, password: parent.password }, 200);

  const settings = await request.put("/api/notifications/settings", {
    ...auth,
    data: {
      absenceAlertsEnabled: true,
      absenceAlertChannel: "WHATSAPP_SMS",
      alertLanguage: "en",
      quietHoursEnabled: false,
      quietHoursStart: "21:00",
      quietHoursEnd: "07:00",
    },
  });
  expect(settings.status(), await settings.text()).toBe(200);

  const { id: vehicleId } = await post("/api/transport/vehicles", {
    registrationNo: BUS,
    type: "BUS",
    seats: 40,
    insuranceExpiry: indiaDate(200),
    fitnessExpiry: indiaDate(200),
    permitExpiry: indiaDate(200),
    pollutionExpiry: indiaDate(100),
  });
  // The bus's GPS device gets its token (shown once) for posting positions.
  const device = await post<{ token: string; endpoint: string; header: string }>(`/api/transport/vehicles/${vehicleId}/device-token`);
  await post("/api/transport/crew", { userId: driverUser, kind: "DRIVER", licenceNo: "TS09 20150001234", licenceExpiry: indiaDate(900), policeVerifiedOn: indiaDate(-60) });
  await post("/api/transport/crew", { userId: attendantUser, kind: "ATTENDANT", policeVerifiedOn: indiaDate(-60) });
  await post("/api/transport/fee-zones", { name: "Zone A", minKm: 0, maxKm: 5, annualFeePaise: 1_200_000 });
  return { attendant, parent, device };
}

/** Opens a menu item from the phone's bottom bar, through "More" when it is not one of the first four. */
async function openFromMenu(page: Page, label: string) {
  const bar = page.getByTestId("bottom-bar");
  await expect(bar).toBeVisible();
  const direct = bar.getByRole("link", { name: label, exact: true });
  if ((await direct.count()) > 0) {
    await direct.click();
    return;
  }
  await bar.getByRole("button", { name: "More" }).click();
  await page.getByRole("dialog", { name: "All sections" }).getByRole("link", { name: label, exact: true }).click();
}

test("the admin creates a route with two stops and puts a student on it; the attendant starts the morning trip on a phone and marks him boarded; his mother sees it and the message is simulated", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const school = newSchool("bus");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const { attendant, parent, device } = await setUpSchool(request, school.schoolCode, { headers: { Authorization: `Bearer ${adminToken}` } });

  // 1. The admin creates the route with its bus, driver and attendant.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Transport", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/transport$/);
  await expect(page.getByText("No routes yet.")).toBeVisible();
  await page.getByTestId("route-new").click();
  const newRoute = page.getByRole("dialog", { name: "New route" });
  await newRoute.getByLabel("Route name", { exact: true }).fill(ROUTE);
  await newRoute.getByLabel("Vehicle", { exact: true }).selectOption({ label: `${BUS} · 40 seats` });
  await newRoute.getByLabel("Driver", { exact: true }).selectOption({ label: "Dev Kumar" });
  await newRoute.getByLabel("Attendant", { exact: true }).selectOption({ label: "Anita Rao" });
  await newRoute.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText(`Route ${ROUTE} created. Add its stops next.`)).toBeVisible();
  await expect(page).toHaveURL(/\/app\/transport\/routes\/[0-9a-f-]+$/);
  const facts = page.getByTestId("route-facts");
  await expect(facts).toContainText(BUS);
  await expect(facts).toContainText("Dev Kumar");
  await expect(facts).toContainText("Anita Rao");

  // 2. Two stops in pickup order, with their times, places and fee zone.
  await page.getByTestId("stops-edit").click();
  const stopsForm = page.getByTestId("stops-form");
  const stops = [
    { name: "Gate Stop", pickup: "07:10", drop: "15:40", at: GATE_STOP },
    { name: "Market Stop", pickup: "07:25", drop: "15:25", at: MARKET_STOP },
  ];
  for (const [i, stop] of stops.entries()) {
    await stopsForm.getByRole("button", { name: "Add a stop" }).click();
    const row = stopsForm.getByTestId("stop-edit-row").nth(i);
    await row.getByLabel("Stop name", { exact: true }).fill(stop.name);
    await row.getByLabel("Pickup", { exact: true }).fill(stop.pickup);
    await row.getByLabel("Drop", { exact: true }).fill(stop.drop);
    await row.getByLabel("Latitude", { exact: true }).fill(String(stop.at.latitude));
    await row.getByLabel("Longitude", { exact: true }).fill(String(stop.at.longitude));
    await row.getByLabel("Fee zone", { exact: true }).selectOption({ label: "Zone A · ₹12,000 a year" });
  }
  await stopsForm.getByRole("button", { name: "Save stops" }).click();
  await expect(page.getByText("Stops saved.")).toBeVisible();
  const routeStops = page.getByTestId("route-stops");
  await expect(routeStops.getByTestId("stop-row")).toHaveCount(2);
  await expect(routeStops.getByTestId("stop-row").nth(0)).toContainText("Gate Stop");
  await expect(routeStops.getByTestId("stop-row").nth(1)).toContainText("Market Stop");
  await expectNoHorizontalScroll(page);

  // 3. She puts Arjun on the route at Market Stop from today.
  await page.getByRole("link", { name: "Add students" }).click();
  await expect(page).toHaveURL(/\/app\/transport\/allocations\?routeId=/);
  await page.getByTestId("alloc-one").click();
  const allocate = page.getByRole("dialog", { name: "Put a student on a bus" });
  await allocate.getByRole("searchbox", { name: "Find a student by name or admission number" }).fill("Arjun");
  await allocate.getByRole("list", { name: "Students" }).getByRole("button", { name: /Arjun Sharma/ }).click();
  await expect(allocate.getByTestId("alloc-picked")).toContainText("Arjun Sharma");
  await expect(allocate.getByLabel("Route", { exact: true })).toHaveValue(/[0-9a-f-]{36}/);
  await expect(allocate.getByText("0 of 40 seats taken")).toBeVisible();
  const stopSelect = allocate.getByLabel("Stop", { exact: true });
  await expect(stopSelect.locator("option", { hasText: "Market Stop" })).toHaveCount(1);
  await stopSelect.selectOption({ label: "Market Stop — Zone A · ₹12,000" });
  await expect(allocate.getByLabel("First day on the bus", { exact: true })).toHaveValue(indiaDate());
  await allocate.getByRole("button", { name: "Put on the bus", exact: true }).click();
  await expect(page.getByText(`Arjun Sharma is on ${ROUTE}.`)).toBeVisible();
  const allocation = page.getByTestId("allocation-row").filter({ hasText: "Arjun Sharma" });
  await expect(allocation).toContainText("Market Stop");
  await expect(allocation).toContainText("₹12,000 a year");

  // 4. The attendant, on her phone, starts the morning trip.
  const attendantPhone = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const tripPage = await attendantPhone.newPage();
  await loginViaUi(tripPage, attendant);
  await expect(tripPage).toHaveURL(/\/app\/dashboard$/);
  await openFromMenu(tripPage, "Bus trips");
  await expect(tripPage).toHaveURL(/\/app\/transport\/trip$/);
  const card = tripPage.getByTestId("trip-route");
  await expect(card).toContainText(ROUTE);
  await expect(card).toContainText("1 student · You are the attendant");
  await card.getByTestId("trip-open-MORNING").click();
  await expect(tripPage.getByTestId("trip-not-started")).toContainText("Not started yet. 1 student travels on this route.");
  await tripPage.getByRole("button", { name: "Start Morning trip" }).click();
  await expect(tripPage.getByText("Trip started.")).toBeVisible();
  await expect(tripPage.getByTestId("trip-panel")).toContainText("Running");
  await expect(tripPage.getByTestId("trip-stop")).toHaveCount(2);

  // The bus's GPS device reports it at Gate Stop (with its token; no sign-in).
  const fix = await request.post(device.endpoint, {
    headers: { [device.header]: device.token },
    data: { latitude: GATE_STOP.latitude + 0.0004, longitude: GATE_STOP.longitude, speedKmh: 18 },
  });
  expect(fix.status(), await fix.text()).toBe(202);
  expect(await fix.json()).toEqual({ accepted: true, onTrip: true });
  const forged = await request.post(device.endpoint, {
    headers: { [device.header]: "not-the-token" },
    data: { latitude: GATE_STOP.latitude, longitude: GATE_STOP.longitude },
  });
  expect(forged.status()).toBe(401);

  // At Market Stop she marks Arjun boarded.
  const marks = tripPage.getByRole("group", { name: "Mark Arjun Sharma" });
  const boarded = marks.getByRole("button", { name: "Boarded" });
  await boarded.click();
  await expect(boarded).toHaveAttribute("aria-pressed", "true");
  await expect(tripPage.getByTestId("trip-counts")).toContainText("Boarded1");
  await expectNoHorizontalScroll(tripPage);

  // 5. His mother, on her phone, sees that he boarded and where the bus is.
  const parentPhone = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const parentPage = await parentPhone.newPage();
  await loginViaUi(parentPage, parent);
  await expect(parentPage).toHaveURL(/\/app\/dashboard$/);
  await openFromMenu(parentPage, "Bus");
  await expect(parentPage).toHaveURL(/\/app\/family\/bus$/);
  const child = parentPage.getByTestId("family-bus-child");
  await expect(child).toContainText("Arjun Sharma");
  await expect(child.getByTestId("family-bus-route")).toContainText(ROUTE);
  await expect(child.getByTestId("family-bus-route")).toContainText("Stop: Market Stop");
  await expect(child.getByTestId("family-bus-route")).toContainText("Pickup 07:25 · Drop 15:25");
  const morning = child.getByTestId("family-bus-trip-MORNING");
  await expect(morning).toContainText("On the way");
  await expect(morning.getByTestId("family-bus-child-status")).toHaveText(/Boarded/);
  await expect(morning.getByTestId("family-bus-position")).toContainText("Near Gate Stop");
  await expect(morning.getByTestId("family-bus-events")).toContainText("Boarded at Market Stop");
  await expectNoHorizontalScroll(parentPage);
  // The parent cannot run trips or read the routes.
  const parentAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, parent)}` } };
  expect((await request.get("/api/transport/routes", parentAuth)).status()).toBe(403);
  expect((await request.get("/api/transport/my-trips", parentAuth)).status()).toBe(403);
  await attendantPhone.close();
  await parentPhone.close();

  // 6. The admin finds the boarding message in the message log once the dispatcher has run (every 15 seconds).
  await page.goto("/app/dashboard");
  await mainNav(page).getByRole("link", { name: "Messages" }).click();
  await expect(page).toHaveURL(/\/app\/messages$/);
  const row = page.getByTestId("messages-table").getByRole("row").filter({ hasText: "Arjun Sharma (Class 5 A)" });
  await expect(async () => {
    await page.reload();
    await expect(row).toContainText("Simulated", { timeout: 3_000 });
  }).toPass({ timeout: 60_000, intervals: [2_000] });
  await expect(row).toContainText("Child boarded the bus");
  await expect(row).toContainText("Neha Sharma");
  await expect(row).toContainText("98765•••01");
  await expect(page.locator("body")).not.toContainText("9876540001");
  await row.getByRole("button", { name: "View Arjun Sharma (Class 5 A)" }).click();
  const detail = page.getByRole("dialog", { name: "Message" });
  await expect(detail.getByTestId("message-detail")).toContainText(
    `Dear Neha Sharma, Arjun Sharma boarded the ${school.schoolName} bus (${ROUTE}) at Market Stop at`,
  );
});
