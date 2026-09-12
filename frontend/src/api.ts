import { ref } from "vue";
export interface User {
  username: string;
  roles: string[];
}
export const user = ref<User | null>(null);
export const sessionNotice = ref("");
let csrf: { token: string; headerName: string } | null = null;
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) {
    if (!csrf) csrf = await api("/api/auth/csrf");
    headers[csrf!.headerName] = csrf!.token;
    if (!(body instanceof FormData) && !(body instanceof URLSearchParams))
      headers["Content-Type"] = "application/json";
  }
  const response = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    credentials: "same-origin",
    headers,
    body:
      body === undefined
        ? undefined
        : body instanceof FormData || body instanceof URLSearchParams
          ? body
          : JSON.stringify(body),
  });
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(
      response.status,
      "서버 응답을 읽을 수 없습니다. 연결 상태를 확인하세요.",
    );
  }
  if (!response.ok) {
    if (response.status === 401) {
      user.value = null;
      csrf = null;
      if (data?.code === "SESSION_REVOKED")
        sessionNotice.value =
          "계정 보안 설정이 변경되어 로그아웃됐습니다. 다시 로그인해 주세요.";
    }
    const messages: Record<string, string> = {
      INVALID_CREDENTIALS: "계정명 또는 비밀번호를 확인하세요.",
      UNAUTHENTICATED: "로그인이 필요합니다.",
      FORBIDDEN: "권한이 없거나 세션이 만료됐습니다. 새로 로그인해 주세요.",
    };
    const details = data?.errors
      ?.map(
        (e: { file?: string; row?: number; field?: string; message: string }) =>
          `${e.file ? `${e.file} ${e.row}행 ` : ""}${e.field || ""}: ${e.message}`,
      )
      .join("\n");
    throw new ApiError(
      response.status,
      details ||
        data?.message ||
        messages[data?.code] ||
        "요청 처리에 실패했습니다.",
    );
  }
  return data as T;
}
export async function restoreSession() {
  try {
    user.value = await api("/api/auth/me");
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) throw e;
  }
}
export async function login(username: string, password: string) {
  csrf = null;
  await api("/api/auth/login", new URLSearchParams({ username, password }));
  csrf = null;
  await restoreSession();
  sessionNotice.value = "";
}
export async function logout() {
  await api("/api/auth/logout", {});
  user.value = null;
  csrf = null;
}
export const errorText = (e: unknown) =>
  e instanceof Error ? e.message : "요청 처리에 실패했습니다.";
export const dateText = (value: string | null) =>
  value ? new Date(value.replace(" ", "T")).toLocaleString("ko-KR") : "—";
export const labels: Record<string, string> = {
  OPEN: "진행 중",
  CLOSED: "종료",
  DRAFT: "검토 대기",
  APPROVED: "승인 완료",
  SUPPLIER: "공급사 요청",
  OFFICIAL: "공식 공고",
  INTERNAL: "내부 확인",
  TARGET: "회수 대상",
  NON_TARGET: "비대상",
  NEEDS_REVIEW: "확인 필요",
  IN_PROGRESS: "작업 중",
  COMPLETED: "완료",
  CANCELLED: "취소",
  MATCHED: "연결 확인",
  EXCLUDED: "연결 제외",
  PENDING: "검토 대기",
  ACCEPTED: "승인",
  REJECTED: "반려",
  QUARANTINE: "재고 격리",
  SHIPMENT_HOLD: "출고 보류",
  SALES_HOLD: "판매 보류",
  SUPPLIER_CHECK: "공급사 확인",
  RETURN_CONFIRMATION: "반품 확인",
  NOTICE_PREPARATION: "안내 준비",
  CASE: "사건",
  INVENTORY: "재고",
  SHIPMENT: "출고",
};
export const label = (value: string) => labels[value] || value;
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export interface CaseRow {
  id: string;
  title: string;
  status: string;
  sourceType: string;
  createdAt: string;
  draftCount: number;
  openTaskCount: number;
}
export interface CaseDetail extends CaseRow {
  sourceText: string;
  lifecycleVersion: number;
  conditions: { id: string; version: number; status: string }[];
}
export interface Dataset {
  id: string;
  asOf: string;
  createdAt: string;
}
export interface Product {
  id: string;
  name: string;
  manufacturer: string;
  packSize: string;
  unit: string;
}
export interface Rule {
  op: string;
  field?: string;
  values?: string[];
  children?: Rule[];
}
export interface Definition {
  datasetId: string;
  sourceQuote: string;
  rule: Rule;
  productReviews: Record<string, { status: string; reason: string }>;
}
export interface Condition {
  id: string;
  version: number;
  status: string;
  definition: Definition;
  approvedBy: string | null;
  approvedAt: string | null;
}
export interface RunRow {
  id: string;
  conditionId: string;
  datasetId: string;
  createdAt: string;
}
export interface Totals {
  target: number;
  nonTarget: number;
  needsReview: number;
}
export interface Assessment {
  id: string;
  conditionId: string;
  datasetId: string;
  inventoryTotals: Totals;
  shipmentTotals: Totals;
  receipts: {
    receiptId: string;
    productId: string;
    decision: string;
    reason: string;
  }[];
  inventory: {
    inventoryId: string;
    receiptId: string;
    warehouse: string;
    quantity: number;
    holdStatus: string;
    decision: string;
  }[];
  shipments: {
    shipmentId: string;
    orderId: string;
    quantity: number;
    target: number;
    nonTarget: number;
    needsReview: number;
    unlinked: number;
  }[];
}

export async function changeOwnPassword(
  currentPassword: string,
  newPassword: string,
) {
  await api("/api/auth/password", { currentPassword, newPassword });
  csrf = null;
  sessionNotice.value = "비밀번호가 변경됐습니다. 새 비밀번호로 로그인하세요.";
  user.value = null;
}
