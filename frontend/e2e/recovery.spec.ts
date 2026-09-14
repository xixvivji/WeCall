import { test, expect, type Page } from "@playwright/test";
import { credentials, signIn } from "./support";

async function post(page: Page, path: string, body: unknown) {
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const response = await page.request.post(path, {
    data: body,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok()).toBe(true);
  return response.json();
}

for (const kind of ["cases", "datasets"] as const) {
  test(`${kind}: loading, failed page, retry and empty results remain distinct`, async ({
    page,
  }) => {
    await page.goto("/");
    await signIn(page);
    const cases = kind === "cases";
    const endpoint = cases ? "/api/v1/recalls" : "/api/v1/datasets";
    let mode: "page" | "fail" | "empty" = "page";
    await page.route(`**${endpoint}?*`, async (route) => {
      const current = Number(
        new URL(route.request().url()).searchParams.get("page"),
      );
      if (mode === "fail") return route.abort("failed");
      const item = cases
        ? {
            id: crypto.randomUUID(),
            title: `합성 페이지 ${current + 1}`,
            status: "OPEN",
            sourceType: "INTERNAL",
            draftCount: 0,
            openTaskCount: 0,
            createdAt: "2026-09-14T00:00:00Z",
          }
        : {
            id:
              current === 0
                ? "aaaaaaaa-0000-0000-0000-000000000000"
                : "bbbbbbbb-0000-0000-0000-000000000000",
            asOf: "2026-09-14T00:00:00Z",
            createdAt: "2026-09-14T00:00:00Z",
          };
      await route.fulfill({
        json: {
          items: mode === "empty" ? [] : [item],
          page: current,
          size: cases ? 12 : 20,
          totalElements: mode === "empty" ? 0 : 2,
          totalPages: mode === "empty" ? 0 : 2,
        },
      });
    });
    await page.goto(cases ? "/" : "/#/datasets");
    const empty = page.getByText(
      cases ? "조건에 맞는 사건이 없습니다" : "등록된 데이터가 없습니다.",
      { exact: true },
    );
    await expect(page.locator(".list-panel tbody tr")).toHaveCount(1);
    await expect(
      page.getByRole("button", { name: "이전", exact: true }),
    ).toBeDisabled();
    mode = "fail";
    await page.getByRole("button", { name: "다음", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText(
      "서버에 연결할 수 없습니다",
    );
    await expect(empty).not.toBeVisible();
    await expect(page.locator(".list-panel tbody tr")).toHaveCount(0);
    mode = "page";
    await page.getByRole("button", { name: "다시 시도", exact: true }).click();
    await expect(page.locator(".list-panel tbody tr")).toContainText(
      cases ? "합성 페이지 2" : "bbbbbbbb",
    );
    await expect(
      page.getByRole("button", { name: "다음", exact: true }),
    ).toBeDisabled();
    mode = "empty";
    await page.getByRole("button", { name: "이전", exact: true }).click();
    await expect(empty).toBeVisible();
    await expect(page.getByRole("alert")).not.toBeVisible();
    await expect(
      page.getByRole("button", { name: "다음", exact: true }),
    ).toBeDisabled();
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });
}

test("case creation preserves input after rejection and ignores repeated submit events", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.getByRole("button", { name: "새 사건 등록", exact: true }).click();
  const title = "중복 제출 검증 " + Date.now();
  await page.getByLabel("사건명", { exact: true }).fill(title);
  await page
    .getByLabel("회수 원문")
    .fill("합성 회수 요청. 실제 업무 기록 아님.");
  let attempts = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/recalls", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    attempts++;
    if (attempts === 1)
      return route.fulfill({
        status: 400,
        json: { message: "합성 입력 거절" },
      });
    await gate;
    await route.continue();
  });
  await page.getByRole("button", { name: "사건 등록", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("합성 입력 거절");
  await expect(page.getByLabel("사건명", { exact: true })).toHaveValue(title);
  const form = page
    .locator("form")
    .filter({ has: page.getByLabel("사건명", { exact: true }) });
  try {
    await form.evaluate((form) => {
      form.dispatchEvent(
        new Event("submit", { bubbles: true, cancelable: true }),
      );
      form.dispatchEvent(
        new Event("submit", { bubbles: true, cancelable: true }),
      );
    });
    await expect(
      page.getByRole("button", { name: "등록 중…", exact: true }),
    ).toBeDisabled();
    await expect.poll(() => attempts).toBe(2);
  } finally {
    release();
  }
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  expect(attempts).toBe(2);
  const rows = await (
    await page.request.get(`/api/v1/recalls?q=${encodeURIComponent(title)}`)
  ).json();
  expect(rows.totalElements).toBe(1);
});

test("two sessions encounter a real task version conflict and explicitly reload before retry", async ({
  page,
  browser,
}) => {
  await page.goto("/");
  await signIn(page);
  const recall = await post(page, "/api/v1/recalls", {
    title: "동시 수정 검증 " + Date.now(),
    sourceType: "INTERNAL",
    sourceText: "합성 작업 충돌 검증",
  });
  const task = await post(page, `/api/v1/recalls/${recall.id}/tasks`, {
    taskType: "SALES_HOLD",
    targetType: "CASE",
    title: "합성 동시 작업",
    instructions: "충돌 후 최신 정보 확인",
    assignee: credentials.WECALL_BOOTSTRAP_OPERATOR_USERNAME,
  });
  const path = `/api/v1/recalls/${recall.id}/tasks/${task.id}`;
  await page.goto(`/#/recalls/${recall.id}?task=${task.id}`);
  await expect(
    page.getByRole("button", { name: "작업 시작", exact: true }),
  ).toBeVisible();
  const context = await browser.newContext({
    baseURL: "http://127.0.0.1:5173",
  });
  try {
    const other = await context.newPage();
    await other.goto("/");
    await signIn(other);
    await post(other, path + "/assignment", {
      expectedVersion: task.version,
      assignee: credentials.WECALL_BOOTSTRAP_OPERATOR_USERNAME,
      note: "다른 세션에서 배정 확인",
    });
    await page.getByLabel("상태 변경 사유").fill("보존할 작업 시작 사유");
    await page.getByRole("button", { name: "작업 시작", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("최신 작업 불러오기");
    await expect(
      page.getByRole("button", { name: "작업 시작", exact: true }),
    ).toBeDisabled();
    await expect(page.getByLabel("상태 변경 사유")).toHaveValue(
      "보존할 작업 시작 사유",
    );
    await page
      .getByRole("button", { name: "최신 작업 불러오기", exact: true })
      .click();
    await expect(
      page.getByRole("status").filter({ hasText: "최신 작업을 불러왔습니다" }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "작업 시작", exact: true }),
    ).toBeEnabled();
    await page.getByRole("button", { name: "작업 시작", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "증빙 제출", exact: true }),
    ).toBeVisible();
    const saved = await (await page.request.get(path)).json();
    expect(saved.status).toBe("IN_PROGRESS");
    expect(saved.version).toBe(task.version + 2);
    expect(
      saved.events.filter((event: { type: string }) => event.type === "START"),
    ).toHaveLength(1);
  } finally {
    await context.close();
  }
});

for (const kind of ["reviews", "workspace"] as const) {
  test(`${kind}: no matching results and query retry`, async ({ page }) => {
    await page.goto("/");
    await signIn(page);
    const endpoint = kind === "reviews" ? "reviews" : "tasks";
    let fail = true;
    await page.route(`**/api/v1/workspace/${endpoint}?*`, (route) =>
      fail
        ? route.fulfill({ status: 503, json: { message: "합성 일시 장애" } })
        : route.fulfill({
            json: {
              items: [],
              page: 0,
              size: 20,
              totalElements: 0,
              totalPages: 0,
              counts: { CONDITION: 0, EVIDENCE: 0, PROOF: 0 },
            },
          }),
    );
    await page.goto(`/#/${kind}`);
    await expect(page.getByRole("alert")).toContainText("합성 일시 장애");
    const empty = page.getByText(
      kind === "reviews"
        ? "조회 조건에 맞는 검토 대기 항목이 없습니다."
        : "조회 조건에 맞는 작업이 없습니다.",
      { exact: true },
    );
    await expect(empty).not.toBeVisible();
    fail = false;
    await page.getByRole("button", { name: "다시 시도", exact: true }).click();
    await expect(empty).toBeVisible();
    await expect(
      page.getByRole("button", { name: "다음", exact: true }),
    ).toBeDisabled();
    await expect(page.getByRole("alert")).not.toBeVisible();
  });
}

test("case task and evidence tabs distinguish read failures from empty lists", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const recall = await post(page, "/api/v1/recalls", {
    title: "빈 업무 목록 검증 " + Date.now(),
    sourceType: "INTERNAL",
    sourceText: "합성 자료",
  });
  let fail = true;
  await page.route(`**/api/v1/recalls/${recall.id}/tasks`, (route) =>
    fail ? route.abort("failed") : route.continue(),
  );
  await page.route(`**/api/v1/recalls/${recall.id}/evidence?*`, (route) =>
    fail ? route.abort("failed") : route.continue(),
  );
  await page.goto(`/#/recalls/${recall.id}`);
  await page.getByRole("button", { name: "대응 작업", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "서버에 연결할 수 없습니다",
  );
  await expect(
    page.getByText("등록된 대응 작업이 없습니다.", { exact: true }),
  ).not.toBeVisible();
  fail = false;
  await page
    .getByRole("button", { name: "작업 목록 다시 시도", exact: true })
    .click();
  await expect(
    page.getByText("등록된 대응 작업이 없습니다.", { exact: true }),
  ).toBeVisible();
  fail = true;
  await page.getByRole("button", { name: "입고 증거", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "서버에 연결할 수 없습니다",
  );
  await expect(
    page.getByText("등록된 입고 증거가 없습니다.", { exact: true }),
  ).not.toBeVisible();
  fail = false;
  await page
    .getByRole("button", { name: "증거 목록 다시 시도", exact: true })
    .click();
  await expect(
    page.getByText("등록된 입고 증거가 없습니다.", { exact: true }),
  ).toBeVisible();
});
