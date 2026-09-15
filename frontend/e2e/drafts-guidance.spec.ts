import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { signIn, credentials } from "./support";
async function post(page: Page, path: string, data: unknown) {
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const r = await page.request.post(path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(r.ok()).toBe(true);
  return r.json();
}
async function createCase(page: Page) {
  return post(page, "/api/v1/recalls", {
    title: "입력 보호 합성 " + Date.now(),
    sourceType: "INTERNAL",
    sourceText: "제조번호 A01 회수 합성 원문",
  });
}
test("unsaved case input cancels navigation, close and reload; successful save clears the guard", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.getByRole("button", { name: "새 사건 등록" }).click();
  const title = "입력 보존 " + Date.now();
  await page.getByLabel("사건명", { exact: true }).fill(title);
  await page.getByLabel("회수 원문").fill("합성 입력");
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("link", { name: "데이터 관리", exact: true }).click();
  await expect(page.getByLabel("사건명", { exact: true })).toHaveValue(title);
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "등록 닫기", exact: true }).click();
  await expect(page.getByLabel("회수 원문")).toHaveValue("합성 입력");
  const beforeUnload = page.waitForEvent("dialog");
  const reload = page.reload().catch(() => null);
  const dialog = await beforeUnload;
  expect(dialog.type()).toBe("beforeunload");
  await dialog.dismiss();
  await reload;
  await expect(page.getByLabel("회수 원문")).toHaveValue("합성 입력");
  await page.route(
    "**/api/v1/recalls",
    (route) =>
      route.request().method() === "POST"
        ? route.fulfill({
            status: 503,
            contentType: "application/json",
            body: JSON.stringify({ message: "합성 저장 실패" }),
          })
        : route.continue(),
    { times: 1 },
  );
  await page.getByRole("button", { name: "사건 등록", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("합성 저장 실패");
  await expect(page.getByLabel("사건명", { exact: true })).toHaveValue(title);
  let unexpected = 0;
  page.on("dialog", async (d) => {
    unexpected++;
    await d.dismiss();
  });
  await page.getByRole("button", { name: "사건 등록", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "대응 작업", exact: true }).click();
  await expect(page).toHaveURL(/tab=tasks/);
  expect(unexpected).toBe(0);
});
test("failed attachment submission preserves bytes and draft; discard and save have distinct behavior", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const recall = await createCase(page);
  const task = await post(page, `/api/v1/recalls/${recall.id}/tasks`, {
    title: "입력 보호 작업",
    instructions: "합성",
    taskType: "QUARANTINE",
    targetType: "CASE",
    assignee: credentials.WECALL_BOOTSTRAP_USERNAME,
  });
  await post(
    page,
    `/api/v1/recalls/${recall.id}/tasks/${task.id}/transitions`,
    { expectedVersion: 0, action: "START", note: "시작" },
  );
  await page.goto(`/#/recalls/${recall.id}?task=${task.id}`);
  await page.getByLabel("처리 증빙", { exact: true }).fill("보존할 합성 증빙");
  await page
    .getByLabel("작업 증빙 파일", { exact: true })
    .setInputFiles(resolve("../samples/attachments/synthetic-checker.png"));
  await page.route(
    `**/api/v1/recalls/${recall.id}/tasks/${task.id}/proofs`,
    (route) =>
      route.fulfill({
        status: 422,
        contentType: "application/json",
        body: JSON.stringify({ message: "합성 파일 검사 거부" }),
      }),
    { times: 1 },
  );
  await page.getByRole("button", { name: "증빙 제출", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("합성 파일 검사 거부");
  page.once("dialog", (d) => d.dismiss());
  await page.getByRole("button", { name: "대응 보고서", exact: true }).click();
  await expect(page.getByLabel("처리 증빙", { exact: true })).toHaveValue(
    "보존할 합성 증빙",
  );
  page.once("dialog", (d) => d.dismiss());
  await page.getByLabel("조회·작업 기준 판정").selectOption("");
  await expect(page).toHaveURL(new RegExp("task=" + task.id));

  expect(
    await page
      .getByLabel("작업 증빙 파일", { exact: true })
      .evaluate((el: HTMLInputElement) => el.files?.[0]?.name),
  ).toBe("synthetic-checker.png");
  page.once("dialog", (d) => d.dismiss());
  await page.getByRole("button", { name: "상세 닫기", exact: true }).click();
  await expect(page.getByLabel("처리 증빙", { exact: true })).toHaveValue(
    "보존할 합성 증빙",
  );
  await page.getByRole("button", { name: "증빙 제출", exact: true }).click();
  await expect(page.getByLabel("처리 증빙", { exact: true })).toHaveValue("");
  await expect(
    page.getByRole("button", { name: "증빙 제출", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "대응 보고서", exact: true }).click();
  await expect(page).toHaveURL(/tab=report/);
  await page
    .getByRole("button", { name: "원문 · 조건 검토", exact: true })
    .click();
  await page.getByRole("button", { name: "첫 조건 작성", exact: true }).click();
  await page.getByLabel("원문 근거 인용").fill("제조번호 A01");
  page.once("dialog", (d) => d.accept());
  await page.getByRole("button", { name: "업무 이력", exact: true }).click();
  await expect(page).toHaveURL(/tab=history/);
});
test("next actions follow server approval and assessment state, expose quantities and recover errors", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const recall = await createCase(page);
  await page.goto(`/#/recalls/${recall.id}`);
  const guide = page.getByRole("region", { name: "사건 다음 할 일" });
  await expect(
    guide.getByRole("button", { name: "첫 조건 작성" }),
  ).toBeVisible();
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const multipart: Record<string, any> = { asOf: "2026-09-09T18:00:00+09:00" };
  for (const [key, file] of [
    ["products", "products"],
    ["receipts", "receipts"],
    ["inventory", "inventory"],
    ["shipments", "shipments"],
    ["shipmentAllocations", "shipment_allocations"],
  ])
    multipart[key!] = {
      name: file + ".csv",
      mimeType: "text/csv",
      buffer: readFileSync(resolve("../samples/recall-001", file + ".csv")),
    };
  const uploaded = await page.request.post("/api/v1/datasets", {
    multipart,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(uploaded.ok()).toBe(true);
  const dataset = await uploaded.json();
  const condition = await post(
    page,
    `/api/v1/recalls/${recall.id}/conditions`,
    {
      datasetId: dataset.datasetId,
      sourceQuote: "제조번호 A01",
      rule: { op: "EQ", field: "LOT_NUMBER", values: ["A01"] },
      productReviews: {
        P1: { status: "MATCHED", reason: "합성 확인" },
        P2: { status: "EXCLUDED", reason: "규격" },
        P3: { status: "EXCLUDED", reason: "제조사" },
      },
    },
  );
  await page.reload();
  await expect(
    guide.getByRole("link", { name: "조건 승인 검토 열기" }),
  ).toBeVisible();
  await guide.getByRole("link", { name: "조건 승인 검토 열기" }).click();
  await expect(page).toHaveURL(new RegExp("condition=" + condition.id));
  await post(
    page,
    `/api/v1/recalls/${recall.id}/conditions/${condition.id}/approval`,
    {},
  );
  await page.reload();
  await expect(
    guide.getByRole("link", { name: "조건 승인 검토 열기" }),
  ).toHaveCount(0);
  await expect(
    guide.getByRole("link", { name: "기준 판정 준비 열기" }),
  ).toBeVisible();
  const run = await post(page, `/api/v1/recalls/${recall.id}/assessments`, {
    conditionId: condition.id,
  });
  await page.goto(`/#/recalls/${recall.id}?tab=overview&assessment=${run.id}`);
  await expect(guide).toContainText("20 EA");
  await expect(
    guide.getByRole("link", { name: "확인 필요 출고 확인 열기" }),
  ).toBeVisible();
  await page.route(
    `**/api/v1/recalls/${recall.id}/closure-check?*`,
    (route) => route.abort(),
    { times: 1 },
  );
  await guide.getByRole("button", { name: "할 일 다시 확인" }).click();
  await expect(guide.getByRole("alert")).toBeVisible();
  await expect(
    guide.getByRole("link", { name: "확인 필요 출고 확인 열기" }),
  ).toHaveCount(0);
  await guide.getByRole("button", { name: "할 일 다시 시도" }).click();
  await expect(guide).toContainText("20 EA");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/wecall-next-actions-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await signIn(page, true);
  await expect(guide.getByRole("button", { name: "첫 조건 작성" })).toHaveCount(
    0,
  );
  await expect(guide).toContainText("검토자가 처리합니다");
});
