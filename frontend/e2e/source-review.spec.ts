import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { signIn } from "./support";
async function post(page: Page, path: string, data: unknown) {
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const response = await page.request.post(path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok()).toBe(true);
  return response.json();
}
test("source changes require explicit review and obsolete drafts can be withdrawn", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const title = "원문 재검토 합성 " + Date.now();
  const recall = await post(page, "/api/v1/recalls", {
    title,
    sourceType: "INTERNAL",
    sourceText: "제조번호 A01 회수",
  });
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const multipart: Record<string, any> = { asOf: "2026-09-09T18:00:00+09:00" };
  for (const [field, file] of [
    ["products", "products"],
    ["receipts", "receipts"],
    ["inventory", "inventory"],
    ["shipments", "shipments"],
    ["shipmentAllocations", "shipment_allocations"],
  ])
    multipart[field!] = {
      name: file + ".csv",
      mimeType: "text/csv",
      buffer: readFileSync(resolve("../samples/recall-001", file + ".csv")),
    };
  const upload = await page.request.post("/api/v1/datasets", {
    multipart,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(upload.ok()).toBe(true);
  const dataset = await upload.json();
  const definition = {
    datasetId: dataset.datasetId,
    sourceQuote: "제조번호 A01",
    productReviews: { P1: { status: "MATCHED", reason: "합성 확인" } },
    rule: { op: "EQ", field: "LOT_NUMBER", values: ["A01"] },
  };
  const prefix = `/api/v1/recalls/${recall.id}`;
  const condition = await post(page, prefix + "/conditions", definition);
  await post(page, `${prefix}/conditions/${condition.id}/approval`, {});
  const run = await post(page, prefix + "/assessments", {
    conditionId: condition.id,
  });
  await post(page, prefix + "/source/revisions", {
    expectedVersion: 1,
    sourceText: "제조번호 A01 회수\n연락처 정정",
    note: "문서 정정",
  });
  await page.goto(`/#/recalls/${recall.id}?condition=${condition.id}`);
  const card = page.locator(`#condition-${condition.id}`);
  await expect(
    card.getByText("원문 재검토 필요.", { exact: false }),
  ).toBeVisible();
  await expect(
    card.getByRole("button", { name: "이 조건으로 판정 실행" }),
  ).toBeDisabled();
  const inbox = await (
    await page.request.get(
      "/api/v1/workspace/reviews?kind=SOURCE&q=" + encodeURIComponent(title),
    )
  ).json();
  expect(inbox.totalElements).toBe(1);
  await card.getByRole("button", { name: "원문 영향 없음 검토" }).click();
  await card
    .getByLabel("원문 영향 검토 사유")
    .fill("연락처만 변경되어 조건·상품 연결에 영향 없음");
  await card
    .getByRole("checkbox", {
      name: "조건·상품 연결·근거가 현재 원문에도 그대로 유효함을 확인했습니다.",
    })
    .check();
  await card.getByRole("button", { name: "영향 없음 확인 저장" }).click();
  await expect(
    card.getByRole("button", { name: "이 조건으로 판정 실행" }),
  ).toBeEnabled();
  const stored = await (
    await page.request.get(`${prefix}/assessments/${run.id}`)
  ).json();
  expect(stored).toEqual(run);
  const draft = await post(page, prefix + "/conditions", definition);
  await post(page, prefix + "/source/revisions", {
    expectedVersion: 2,
    sourceText: "제조번호 A01 회수\n담당자 재정정",
    note: "다시 정정",
  });
  await page.reload();
  const draftCard = page.locator(`#condition-${draft.id}`);
  await expect(
    draftCard.getByRole("button", { name: "조건 승인", exact: true }),
  ).toBeDisabled();
  await draftCard.getByRole("button", { name: "조건 초안 철회" }).click();
  await draftCard
    .getByLabel("초안 철회 사유")
    .fill("기존 승인 조건 재검토로 진행하여 중복 초안 철회");
  await draftCard.getByRole("button", { name: "철회 사유 저장" }).click();
  await expect(draftCard.getByText("초안 철회", { exact: true })).toBeVisible();
  await expect(
    draftCard.getByRole("button", { name: "이 조건으로 판정 실행" }),
  ).toHaveCount(0);
  await expect(
    card.getByRole("button", { name: "이 조건으로 판정 실행" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await signIn(page, true);
  await page.goto(`/#/recalls/${recall.id}?condition=${condition.id}`);
  await expect(
    page.getByText("원문 재검토 필요.", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "원문 영향 없음 검토" }),
  ).toHaveCount(0);
});
