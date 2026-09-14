import { test, expect, type Page } from "@playwright/test";
import { signIn, credentials } from "./support";
async function post(page: Page, path: string, data: unknown) {
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const response = await page.request.post(path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok()).toBe(true);
  return response.json();
}
test("history paginates real events, filters, recovers reads and is readable by operators", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const recall = await post(page, "/api/v1/recalls", {
    title: "이력 페이지 검증 " + Date.now(),
    sourceType: "INTERNAL",
    sourceText: "합성 이력 검증",
  });
  let task = await post(page, `/api/v1/recalls/${recall.id}/tasks`, {
    title: "합성 이력 작업",
    instructions: "기록 확인",
    taskType: "SALES_HOLD",
    targetType: "CASE",
    assignee: credentials.WECALL_BOOTSTRAP_OPERATOR_USERNAME,
  });
  for (let i = 1; i <= 21; i++)
    task = await post(
      page,
      `/api/v1/recalls/${recall.id}/tasks/${task.id}/assignment`,
      {
        expectedVersion: task.version,
        assignee: credentials.WECALL_BOOTSTRAP_OPERATOR_USERNAME,
        note: `합성 배정 ${i}`,
      },
    );
  await page.goto(`/#/recalls/${recall.id}`);
  await page.getByRole("button", { name: "업무 이력", exact: true }).click();
  await expect(page.locator(".history-entry")).toHaveCount(20);
  await expect(page.locator(".history-entry").first()).toContainText(
    "합성 배정 21",
  );
  await page.getByRole("button", { name: "다음", exact: true }).click();
  await expect(page.locator(".history-entry")).toHaveCount(2);
  await expect(
    page.getByRole("button", { name: "다음", exact: true }),
  ).toBeDisabled();
  await page.getByLabel("이력 정렬").selectOption("ASC");
  await page.getByRole("button", { name: "이력 조회", exact: true }).click();
  await expect(page.locator(".history-entry").first()).toContainText(
    "작업 등록",
  );
  await expect(
    page.getByRole("button", { name: "이전", exact: true }),
  ).toBeDisabled();
  await page.getByLabel("이력 유형").selectOption("LIFECYCLE");
  await page.getByRole("button", { name: "이력 조회", exact: true }).click();
  await expect(
    page.getByText("조회 조건에 맞는 업무 이력이 없습니다."),
  ).toBeVisible();
  await page.route(
    `**/api/v1/recalls/${recall.id}/history?*`,
    (route) => route.abort("failed"),
    { times: 1 },
  );
  await page.getByLabel("이력 유형").selectOption("TASK");
  await page.getByRole("button", { name: "이력 조회", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "서버에 연결할 수 없습니다",
  );
  await expect(
    page.getByText("조회 조건에 맞는 업무 이력이 없습니다."),
  ).not.toBeVisible();
  await page.getByRole("button", { name: "다시 시도", exact: true }).click();
  await expect(page.locator(".history-entry")).toHaveCount(20);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/wecall-history-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await signIn(page, true);
  await page.getByRole("button", { name: "업무 이력", exact: true }).click();
  await expect(page.locator(".history-entry")).toHaveCount(20);
  await page
    .locator(".history-entry")
    .first()
    .getByRole("link", { name: "관련 상세 열기" })
    .click();
  await expect(
    page.getByRole("button", { name: "최신 작업 불러오기", exact: true }),
  ).toBeVisible();
});
