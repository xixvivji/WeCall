import { test, expect } from "@playwright/test";
import { signIn } from "./support";

test("account failures retry without empty-state ambiguity or losing status notes on mobile", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await signIn(page);
  let failList = true,
    failEvents = true;
  await page.route("**/api/users", (route) =>
    route.fulfill({
      status: failList ? 503 : 200,
      contentType: "application/json",
      body: JSON.stringify(
        failList
          ? { message: "합성 조회 실패" }
          : [
              {
                username: "synthetic-account",
                displayName: "긴합성표시이름".repeat(30),
                role: "OPERATOR",
                enabled: true,
                version: 0,
              },
            ],
      ),
    }),
  );
  await page.route("**/api/users/synthetic-account/events", (route) =>
    route.fulfill({
      status: failEvents ? 503 : 200,
      contentType: "application/json",
      body: JSON.stringify(failEvents ? { message: "합성 이력 실패" } : []),
    }),
  );
  await page.goto("/#/users");
  await expect(
    page.getByRole("button", { name: "계정 다시 조회" }),
  ).toBeVisible();
  await expect(page.getByText("등록된 계정이 없습니다.")).toHaveCount(0);
  failList = false;
  await page.getByRole("button", { name: "계정 다시 조회" }).click();
  const table = page.getByRole("region", { name: "팀 계정 표" });
  await expect(table).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(await table.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(
    true,
  );
  await table.focus();
  await page.keyboard.press("End");
  await page.getByRole("button", { name: "상태 · 이력" }).click();
  await expect(
    page.getByRole("button", { name: "변경 이력 다시 조회" }),
  ).toBeVisible();
  await expect(page.getByText("변경 기록이 없습니다.")).toHaveCount(0);
  await page.getByLabel("계정 상태 변경 사유").fill("유지할 합성 메모");
  failEvents = false;
  await page.getByRole("button", { name: "변경 이력 다시 조회" }).click();
  await expect(page.getByText("변경 기록이 없습니다.")).toBeVisible();
  await expect(page.getByLabel("계정 상태 변경 사유")).toHaveValue(
    "유지할 합성 메모",
  );
  await page.screenshot({
    path: "/tmp/wecall-mobile-states.png",
    fullPage: true,
  });
});

test("dataset picker separates pending, failed and empty reads and retries", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const response = await page.request.post("/api/v1/recalls", {
    headers: { [csrf.headerName]: csrf.token },
    data: {
      title: "데이터 선택 합성 " + Date.now(),
      sourceType: "INTERNAL",
      sourceText: "합성 원문",
    },
  });
  expect(response.ok()).toBe(true);
  const recall = await response.json();
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  let fail = true;
  await page.route("**/api/v1/datasets?*", async (route) => {
    await pending;
    await route.fulfill({
      status: fail ? 503 : 200,
      contentType: "application/json",
      body: JSON.stringify(
        fail
          ? { message: "합성 실패" }
          : { items: [], totalPages: 0, totalElements: 0, page: 0, size: 20 },
      ),
    });
  });
  await page.goto(`/#/recalls/${recall.id}`);
  await page.getByRole("button", { name: "첫 조건 작성" }).click();
  await expect(
    page.getByText("데이터 버전을 불러오는 중입니다…"),
  ).toBeVisible();
  await expect(
    page.getByText("선택할 데이터 버전이 없습니다.", { exact: false }),
  ).toHaveCount(0);
  release();
  await expect(
    page.getByRole("button", { name: "데이터 버전 다시 조회" }),
  ).toBeVisible();
  await expect(page.getByLabel("판정에 사용할 데이터 버전")).toBeDisabled();
  fail = false;
  await page.getByRole("button", { name: "데이터 버전 다시 조회" }).click();
  await expect(
    page.getByText("선택할 데이터 버전이 없습니다.", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "데이터 버전 다시 조회" }),
  ).toHaveCount(0);
});
