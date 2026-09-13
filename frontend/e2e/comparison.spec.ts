import { test, expect } from "@playwright/test";
import { compareAssessments } from "../src/comparison";
import type { Assessment } from "../src/api";
function fixture(): Assessment {
  return {
    id: "a",
    conditionId: "c",
    datasetId: "d",
    inventoryTotals: { target: 0, nonTarget: 0, needsReview: 0 },
    shipmentTotals: { target: 0, nonTarget: 0, needsReview: 0 },
    receipts: [],
    inventory: [],
    shipments: [],
  };
}
test("comparison preserves missing records and input snapshots", () => {
  const a = fixture(),
    b = fixture();
  a.inventory = [
    {
      inventoryId: "I1",
      receiptId: "R1",
      warehouse: "창고",
      quantity: 10,
      holdStatus: "NONE",
      decision: "TARGET",
    },
  ];
  b.inventory = [{ ...a.inventory[0]!, inventoryId: "I2", quantity: 0 }];
  const original = JSON.stringify([a, b]);
  const rows = compareAssessments(a, b).inventory;
  expect(rows.map((r) => [r.id, r.kind])).toEqual([
    ["I1", "removed"],
    ["I2", "added"],
  ]);
  expect(rows[0]!.after).toBeNull();
  expect(rows[1]!.after).toContain("0 EA");
  expect(JSON.stringify([a, b])).toBe(original);
});
test("comparison detects relationship and unlinked quantity changes", () => {
  const a = fixture(),
    b = fixture();
  a.shipments = [
    {
      shipmentId: "S2",
      orderId: "O2",
      quantity: 30,
      target: 20,
      nonTarget: 0,
      needsReview: 10,
      unlinked: 10,
    },
  ];
  b.shipments = [{ ...a.shipments[0]!, orderId: "OTHER", unlinked: 0 }];
  const row = compareAssessments(a, b).shipments[0]!;
  expect(row.changed).toBe(true);
  expect(row.before).toContain("연결 미확인 10");
  expect(row.after).toContain("주문 OTHER");
  expect(compareAssessments(a, a).shipments[0]!.kind).toBe("same");
});
