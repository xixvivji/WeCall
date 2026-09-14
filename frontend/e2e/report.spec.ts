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
test("report selects stored quantities, recovers failures and prints only the report", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const recall = await post(page, "/api/v1/recalls", {
    title: "보고서 합성 검증 " + Date.now(),
    sourceType: "INTERNAL",
    sourceText: "제조번호 A01 보고서 원문 <script>합성</script>",
  });
  await page.goto(`/#/recalls/${recall.id}`);
  await page.getByRole("button", { name: "대응 보고서", exact: true }).click();
  await expect(
    page.getByText("기준 판정이 선택되지 않았습니다.", { exact: false }),
  ).toBeVisible();
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
  const condition = await post(
    page,
    `/api/v1/recalls/${recall.id}/conditions`,
    {
      datasetId: dataset.datasetId,
      sourceQuote: "제조번호 A01",
      productReviews: {
        P1: { status: "MATCHED", reason: "합성 확인" },
        P2: { status: "EXCLUDED", reason: "규격 다름" },
        P3: { status: "EXCLUDED", reason: "제조사 다름" },
      },
      rule: { op: "EQ", field: "LOT_NUMBER", values: ["A01"] },
    },
  );
  await post(
    page,
    `/api/v1/recalls/${recall.id}/conditions/${condition.id}/approval`,
    {},
  );
  const assessment = await post(
    page,
    `/api/v1/recalls/${recall.id}/assessments`,
    { conditionId: condition.id },
  );
  await post(page, `/api/v1/recalls/${recall.id}/tasks`, {
    title: "미배정 확인 작업",
    instructions: "합성",
    taskType: "SUPPLIER_CHECK",
    targetType: "CASE",
  });
  await page.reload();
  await page.getByRole("button", { name: "대응 보고서", exact: true }).click();
  const report = page.getByRole("region", { name: "사건 대응 보고서" });
  await expect(report).toContainText(assessment.id);
  await expect(report).not.toContainText("Invalid Date");
  await expect(
    report.getByRole("table", { name: "보고서 판정 수량" }),
  ).toContainText(String(assessment.inventoryTotals.target));
  await expect(report).toContainText("미배정 확인 작업");
  await expect(report).toContainText("출고 연결 미확인: 10 EA");
  await page.route(
    `**/api/v1/recalls/${recall.id}/report?*`,
    (route) => route.abort("failed"),
    { times: 1 },
  );
  await page.getByRole("button", { name: "보고서 새로 조회" }).click();
  await expect(report.getByRole("alert")).toBeVisible();
  await expect(report.locator("article")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "인쇄 · PDF 저장" }),
  ).toBeDisabled();
  await report.getByRole("button", { name: "다시 시도" }).click();
  await expect(report.locator("article")).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/wecall-report-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.evaluate(() => {
    window.print = () => {
      document.documentElement.dataset.printCalled = "yes";
    };
  });
  await page.getByRole("button", { name: "인쇄 · PDF 저장" }).click();
  await expect(page.locator("html")).toHaveAttribute(
    "data-print-called",
    "yes",
  );
  await page.emulateMedia({ media: "print" });
  await expect(page.locator(".sidebar")).toBeHidden();
  await expect(
    page.getByRole("navigation", { name: "사건 업무" }),
  ).toBeHidden();
  await expect(
    page.getByRole("button", { name: "인쇄 · PDF 저장" }),
  ).toBeHidden();
  await expect(report.locator("article")).toBeVisible();
  await page.screenshot({
    path: "/tmp/wecall-report-print.png",
    fullPage: true,
  });
  await page.emulateMedia({ media: "screen" });
  await page.getByLabel("조회·작업 기준 판정").selectOption("");
  await expect(
    report.getByRole("table", { name: "보고서 판정 수량" }),
  ).toHaveCount(0);
  await expect(report).toContainText("기준 판정이 선택되지 않았습니다.");
});
