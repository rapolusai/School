import { apiFetch, pathSegment } from "./api";
import { reportQuery, type ReportParams } from "./reports-api";
import type {
  AllotmentView,
  AllotRequest,
  BedView,
  BedsRequest,
  BlockView,
  BoarderDuesReport,
  BoarderView,
  HostelComplaintRequest,
  HostelComplaintStatus,
  HostelComplaintStatusRequest,
  ComplaintView,
  FamilyHostel,
  HostelAlertView,
  HostelBlockRequest,
  HostelDecisionRequest,
  HostelDetail,
  HostelOccupancyReport,
  HostelOutingsReport,
  HostelPassRequest,
  HostelPassView,
  HostelRequest,
  HostelRollCallReport,
  HostelStaffRef,
  HostelStudentRef,
  HostelView,
  MenuRequest,
  MessAttendanceRequest,
  MessDay,
  MoveRequest,
  OccupancyBoard,
  ResolveRequest,
  RollCallRegister,
  RollCallRequest,
  RollCallSheet,
  RollCallSummary,
  RoomRequest,
  RoomView,
  StudentBoarding,
  VacateRequest,
  WardenPassRequest,
  WeeklyMenu,
} from "./types";

/* Endpoint helpers for docs/api/phase-3-hostel.md. */

const id = pathSegment;
const base = "/api/hostel";

export type HostelRangeParams = { from?: string; to?: string };

const query = (params: Record<string, string | null | undefined>) => reportQuery(params as ReportParams);

/** The JSON path and the CSV path (which Excel opens) of each hostel report. */
export const hostelReportPaths = {
  occupancy: () => `${base}/reports/occupancy`,
  occupancyCsv: () => `${base}/reports/occupancy.csv`,
  rollCall: (p: HostelRangeParams) => `${base}/reports/roll-call${query(p)}`,
  rollCallCsv: (p: HostelRangeParams) => `${base}/reports/roll-call.csv${query(p)}`,
  outings: (month: string) => `${base}/reports/outings${query({ month })}`,
  outingsCsv: (month: string) => `${base}/reports/outings.csv${query({ month })}`,
  feeDues: () => `${base}/reports/fee-dues`,
  feeDuesCsv: () => `${base}/reports/fee-dues.csv`,
};

export const hostelApi = {
  /* set-up */
  hostels: () => apiFetch<HostelView[]>(`${base}/hostels`),
  hostel: (hostelId: string) => apiFetch<HostelDetail>(`${base}/hostels/${id(hostelId)}`),
  staff: () => apiFetch<HostelStaffRef[]>(`${base}/staff`),
  students: (q: string) => apiFetch<HostelStudentRef[]>(`${base}/students${query({ q })}`),
  createHostel: (body: HostelRequest) => apiFetch<HostelView>(`${base}/hostels`, { method: "POST", body }),
  updateHostel: (hostelId: string, body: HostelRequest) =>
    apiFetch<HostelView>(`${base}/hostels/${id(hostelId)}`, { method: "PUT", body }),
  addBlock: (hostelId: string, body: HostelBlockRequest) =>
    apiFetch<BlockView>(`${base}/hostels/${id(hostelId)}/blocks`, { method: "POST", body }),
  updateBlock: (hostelId: string, blockId: string, body: HostelBlockRequest) =>
    apiFetch<BlockView>(`${base}/hostels/${id(hostelId)}/blocks/${id(blockId)}`, { method: "PUT", body }),
  removeBlock: (hostelId: string, blockId: string) =>
    apiFetch<void>(`${base}/hostels/${id(hostelId)}/blocks/${id(blockId)}`, { method: "DELETE" }),
  addRoom: (hostelId: string, blockId: string, body: RoomRequest) =>
    apiFetch<RoomView>(`${base}/hostels/${id(hostelId)}/blocks/${id(blockId)}/rooms`, { method: "POST", body }),
  updateRoom: (hostelId: string, roomId: string, body: RoomRequest) =>
    apiFetch<RoomView>(`${base}/hostels/${id(hostelId)}/rooms/${id(roomId)}`, { method: "PUT", body }),
  removeRoom: (hostelId: string, roomId: string) =>
    apiFetch<void>(`${base}/hostels/${id(hostelId)}/rooms/${id(roomId)}`, { method: "DELETE" }),
  addBeds: (hostelId: string, roomId: string, body: BedsRequest) =>
    apiFetch<BedView[]>(`${base}/hostels/${id(hostelId)}/rooms/${id(roomId)}/beds`, { method: "POST", body }),
  removeBed: (hostelId: string, bedId: string) =>
    apiFetch<void>(`${base}/hostels/${id(hostelId)}/beds/${id(bedId)}`, { method: "DELETE" }),
  occupancy: (hostelId: string) => apiFetch<OccupancyBoard>(`${base}/hostels/${id(hostelId)}/occupancy`),

  /* allotment */
  allot: (body: AllotRequest) => apiFetch<AllotmentView>(`${base}/allotments`, { method: "POST", body }),
  vacate: (allotmentId: string, body: VacateRequest) =>
    apiFetch<AllotmentView>(`${base}/allotments/${id(allotmentId)}/vacate`, { method: "POST", body }),
  move: (allotmentId: string, body: MoveRequest) =>
    apiFetch<AllotmentView>(`${base}/allotments/${id(allotmentId)}/move`, { method: "POST", body }),
  boarders: (hostelId?: string) => apiFetch<BoarderView[]>(`${base}/boarders${query({ hostelId })}`),
  boarding: (studentId: string) => apiFetch<StudentBoarding>(`${base}/students/${id(studentId)}/boarding`),

  /* mess */
  menu: (hostelId: string) => apiFetch<WeeklyMenu>(`${base}/hostels/${id(hostelId)}/menu`),
  saveMenu: (hostelId: string, body: MenuRequest) =>
    apiFetch<WeeklyMenu>(`${base}/hostels/${id(hostelId)}/menu`, { method: "PUT", body }),
  messDay: (hostelId: string, date?: string) =>
    apiFetch<MessDay>(`${base}/hostels/${id(hostelId)}/mess-attendance${query({ date })}`),
  markMess: (hostelId: string, date: string, body: MessAttendanceRequest) =>
    apiFetch<MessDay>(`${base}/hostels/${id(hostelId)}/mess-attendance/${id(date)}`, { method: "PUT", body }),

  /* passes */
  passes: (params: { hostelId?: string } & HostelRangeParams = {}) =>
    apiFetch<HostelPassView[]>(`${base}/passes${query(params)}`),
  pass: (passId: string) => apiFetch<HostelPassView>(`${base}/passes/${id(passId)}`),
  raisePass: (body: WardenPassRequest) => apiFetch<HostelPassView>(`${base}/passes`, { method: "POST", body }),
  approvePass: (passId: string, body: HostelDecisionRequest) =>
    apiFetch<HostelPassView>(`${base}/passes/${id(passId)}/approve`, { method: "POST", body }),
  rejectPass: (passId: string, body: HostelDecisionRequest) =>
    apiFetch<HostelPassView>(`${base}/passes/${id(passId)}/reject`, { method: "POST", body }),
  markOut: (passId: string) => apiFetch<HostelPassView>(`${base}/passes/${id(passId)}/out`, { method: "POST" }),
  markIn: (passId: string) => apiFetch<HostelPassView>(`${base}/passes/${id(passId)}/in`, { method: "POST" }),

  /* roll call */
  rollCall: (hostelId: string, night?: string) =>
    apiFetch<RollCallSheet>(`${base}/hostels/${id(hostelId)}/roll-call${query({ night })}`),
  takeRollCall: (hostelId: string, night: string, body: RollCallRequest) =>
    apiFetch<RollCallSheet>(`${base}/hostels/${id(hostelId)}/roll-call/${id(night)}`, { method: "PUT", body }),
  rollCallRegister: (hostelId: string, p: HostelRangeParams) =>
    apiFetch<RollCallRegister>(`${base}/hostels/${id(hostelId)}/roll-call/register${query(p)}`),
  rollCallSummary: (hostelId: string, month: string) =>
    apiFetch<RollCallSummary>(`${base}/hostels/${id(hostelId)}/roll-call/summary${query({ month })}`),
  alerts: () => apiFetch<HostelAlertView[]>(`${base}/alerts`),
  resolveAlert: (alertId: string, body: ResolveRequest) =>
    apiFetch<HostelAlertView>(`${base}/alerts/${id(alertId)}/resolve`, { method: "POST", body }),

  /* complaints */
  complaints: (params: { hostelId?: string; status?: HostelComplaintStatus } = {}) =>
    apiFetch<ComplaintView[]>(`${base}/complaints${query(params)}`),
  raiseComplaint: (body: HostelComplaintRequest) => apiFetch<ComplaintView>(`${base}/complaints`, { method: "POST", body }),
  moveComplaint: (complaintId: string, body: HostelComplaintStatusRequest) =>
    apiFetch<ComplaintView>(`${base}/complaints/${id(complaintId)}/status`, { method: "POST", body }),

  /* reports */
  occupancyReport: () => apiFetch<HostelOccupancyReport>(hostelReportPaths.occupancy()),
  rollCallReport: (p: HostelRangeParams) => apiFetch<HostelRollCallReport>(hostelReportPaths.rollCall(p)),
  outingsReport: (month: string) => apiFetch<HostelOutingsReport>(hostelReportPaths.outings(month)),
  feeDuesReport: () => apiFetch<BoarderDuesReport>(hostelReportPaths.feeDues()),

  /* the family app */
  childHostel: (studentId: string) => apiFetch<FamilyHostel>(`/api/me/children/${id(studentId)}/hostel`),
  childPasses: (studentId: string) => apiFetch<HostelPassView[]>(`/api/me/children/${id(studentId)}/hostel-passes`),
  requestPass: (studentId: string, body: HostelPassRequest) =>
    apiFetch<HostelPassView>(`/api/me/children/${id(studentId)}/hostel-passes`, { method: "POST", body }),
  cancelPass: (studentId: string, passId: string) =>
    apiFetch<HostelPassView>(`/api/me/children/${id(studentId)}/hostel-passes/${id(passId)}/cancel`, { method: "POST" }),
};
