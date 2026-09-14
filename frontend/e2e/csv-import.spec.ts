import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { signIn } from "./support";
const definitions = [
  ["상품 CSV", "products"],
  ["입고 CSV", "receipts"],
  ["재고 CSV", "inventory"],
  ["출고 CSV", "shipments"],
  ["출고·입고 연결 CSV", "shipment_allocations"],
] as const;
const sample = (type: string) =>
  readFileSync(resolve(`../samples/recall-001/${type}.csv`), "utf8");
async function fill(page: Page, replacements: Record<string, string> = {}) {
  await page.getByLabel("데이터 기준 시각").fill("2026-09-09T18:00");
  for (const [label, type] of definitions)
    await page.getByLabel(label, { exact: true }).setInputFiles({
      name: `upload-${type}.csv`,
      mimeType: "text/csv",
      buffer: Buffer.from(replacements[type] ?? sample(type)),
    });
}
async function downloadBytes(page: Page, name: string) {
  const pending = page.waitForEvent("download");
  await page.getByRole("button", { name, exact: true }).click();
  const download = await pending;
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk));
  return {
    filename: download.suggestedFilename(),
    bytes: Buffer.concat(chunks),
  };
}
test("blank CSV downloads and field errors lead to a corrected real import", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.goto("/#/datasets");
  const zip = await downloadBytes(page, "CSV 빈 양식 5종 다운로드");
  expect(zip.filename).toBe("wecall-csv-templates.zip");
  expect(zip.bytes.subarray(0, 2).toString()).toBe("PK");
  await page.getByText("CSV 항목별 작성 안내", { exact: true }).click();
  await page.getByText("상품 · products.csv", { exact: true }).click();
  const csv = await downloadBytes(page, "상품 빈 양식 다운로드");
  expect(csv.filename).toBe("products.csv");
  expect(csv.bytes.toString("utf8")).toBe(
    "\ufeff" + sample("products").split("\n")[0] + "\r\n",
  );
  await fill(page, {
    inventory: sample("inventory").replace(",60,", ",-1,"),
    receipts: sample("receipts").replace("2026-10-31", "2026-02-30"),
  });
  await page
    .getByRole("button", { name: "데이터 검증 후 등록", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "데이터는 등록되지 않았습니다",
  );
  const table = page.getByRole("table", { name: "CSV 검증 오류", exact: true });
  await expect(table.locator("tbody tr")).toHaveCount(2);
  await expect(table).toContainText("upload-inventory.csv");
  await expect(table).toContainText("quantity");
  await expect(table).toContainText("2행");
  await page
    .getByLabel("오류 파일", { exact: true })
    .selectOption("receipts.csv");
  await expect(table.locator("tbody tr")).toHaveCount(1);
  await expect(table).toContainText("expiry_date");
  expect(
    await page
      .getByLabel("재고 CSV", { exact: true })
      .evaluate((input: HTMLInputElement) => input.files?.[0]?.name),
  ).toBe("upload-inventory.csv");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page
    .getByRole("region", { name: "CSV 오류 내역" })
    .scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "/tmp/wecall-csv-errors-mobile.png",
    fullPage: false,
  });
  await fill(page);
  await page
    .getByRole("button", { name: "데이터 검증 후 등록", exact: true })
    .click();
  await expect(
    page.getByRole("status").filter({ hasText: "등록 완료" }),
  ).toContainText("10 EA");
  await expect(table).not.toBeVisible();
  await expect(page.getByRole("alert")).not.toBeVisible();
});
test("large error lists paginate and malformed files have no invented row number", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.goto("/#/datasets");
  const inventory =
    sample("inventory").split("\n")[0] +
    "\n" +
    Array.from({ length: 250 }, (_, i) => `BAD${i},R1,WH1,-1,NONE`).join("\n") +
    "\n";
  await fill(page, {
    products: sample("products").split("\n")[0] + '\nP1,"닫히지 않은 문자열',
    inventory,
  });
  await page
    .getByRole("button", { name: "데이터 검증 후 등록", exact: true })
    .click();
  await expect(
    page.getByRole("region", { name: "CSV 오류 내역" }),
  ).toContainText("251건");
  const table = page.getByRole("table", { name: "CSV 검증 오류", exact: true });
  await expect(table.locator("tbody tr")).toHaveCount(20);
  await page.getByRole("button", { name: "오류 다음", exact: true }).click();
  await expect(table.locator("tbody tr")).toHaveCount(20);
  await page
    .getByLabel("오류 파일", { exact: true })
    .selectOption("products.csv");
  await expect(table.locator("tbody tr")).toHaveCount(1);
  await expect(
    table.getByRole("cell", { name: "파일 전체", exact: true }),
  ).toBeVisible();
  await expect(table).not.toContainText("0행");
  await expect(
    page.getByRole("button", { name: "오류 이전", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "오류 다음", exact: true }),
  ).toBeDisabled();
});
