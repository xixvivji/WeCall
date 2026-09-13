import type { Assessment } from "./api";
export interface ComparisonRow {
  id: string;
  before: string | null;
  after: string | null;
  changed: boolean;
  kind: "added" | "removed" | "changed" | "same";
}
function pair<T>(
  before: T[],
  after: T[],
  key: (row: T) => string,
  describe: (row: T) => string,
): ComparisonRow[] {
  const a = new Map(before.map((row) => [key(row), describe(row)]));
  const b = new Map(after.map((row) => [key(row), describe(row)]));
  return [...new Set([...a.keys(), ...b.keys()])].sort().map((id) => {
    const left = a.get(id) ?? null,
      right = b.get(id) ?? null;
    return {
      id,
      before: left,
      after: right,
      changed: left !== right,
      kind:
        left === null
          ? "added"
          : right === null
            ? "removed"
            : left !== right
              ? "changed"
              : "same",
    };
  });
}
const decision = (value: string) =>
  ({ TARGET: "대상", NON_TARGET: "비대상", NEEDS_REVIEW: "확인 필요" })[
    value
  ] ?? value;
export function compareAssessments(before: Assessment, after: Assessment) {
  return {
    receipts: pair(
      before.receipts,
      after.receipts,
      (r) => r.receiptId,
      (r) => `상품 ${r.productId} · ${decision(r.decision)} · ${r.reason}`,
    ),
    inventory: pair(
      before.inventory,
      after.inventory,
      (r) => r.inventoryId,
      (r) =>
        `입고 ${r.receiptId} · ${r.warehouse} · ${r.quantity} EA · ${decision(r.decision)} · 보류 ${r.holdStatus}`,
    ),
    shipments: pair(
      before.shipments,
      after.shipments,
      (r) => r.shipmentId,
      (r) =>
        `주문 ${r.orderId} · 전체 ${r.quantity} / 대상 ${r.target} / 비대상 ${r.nonTarget} / 확인 필요 ${r.needsReview} / 연결 미확인 ${r.unlinked} EA`,
    ),
  };
}
