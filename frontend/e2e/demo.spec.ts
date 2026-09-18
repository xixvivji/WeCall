import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
const sample = JSON.parse(readFileSync(resolve("src/demo/sample.json"), "utf8"));
import { signIn } from "./support";
const golden = (name: string) =>
  readFileSync(resolve("../samples/recall-001", name), "utf8");
test("demo matches independently authored synthetic sources", () => {
  expect(sample.notice).toBe(golden("notice.md"));
  expect(sample.evidence).toBe(golden("receipt-evidence.md"));
  expect(sample.condition).toEqual(
    JSON.parse(golden("condition-request.json")),
  );
  expect(sample.totals).toEqual(JSON.parse(golden("expected_summary.json")));
  for (const [field, file] of [
    ["products", "products.csv"],
    ["receipts", "receipts.csv"],
    ["inventory", "inventory.csv"],
    ["decisions", "expected_receipt_decisions.csv"],
    ["shipments", "expected_shipment_impacts.csv"],
  ] as const) {
    const [header, ...lines] = golden(file).trim().split("\n");
    const keys = header!.split(",");
    expect(sample[field]).toEqual(
      lines.map((line) =>
        Object.fromEntries(line.split(",").map((v, i) => [keys[i]!, v])),
      ),
    );
  }
});
test("guest walkthrough gates steps, isolates tabs, resets and makes no API requests", async ({
  page,
  context,
}) => {
  const requests: string[] = [];
  page.on("request", (r) => {
    if (new URL(r.url()).pathname.startsWith("/api/")) requests.push(r.url());
  });
  await page.route("**/api/**", (r) => r.abort());
  await page.goto("/#/demo");
  const stages = page.getByRole("navigation", { name: "샘플 체험 단계" });
  await expect(
    stages.getByRole("button", { name: /대응·마무리/ }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "다음: AI 초안" }).click();
  await expect(
    page.getByText("저장된 AI 초안 예시", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "다음: 사람 검토" }).click();
  await expect(
    page.getByRole("button", { name: "샘플 조건 승인하고 영향 보기" }),
  ).toBeDisabled();
  await page
    .getByLabel("원문 조건과 상품의 제조사·규격을 확인했습니다.")
    .check();
  await page
    .getByRole("button", { name: "샘플 조건 승인하고 영향 보기" })
    .click();
  await expect(
    page.getByRole("heading", { name: "영향 확인", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "/tmp/wecall-demo-desktop.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "다음: 입고 보완" }).click();
  await expect(
    page.getByRole("button", { name: "다음: 대응·마무리" }),
  ).toBeDisabled();
  await page
    .getByLabel("입고·상품·전체 수량·날짜·단일 제조분의 근거를 확인했습니다.")
    .check();
  await page
    .getByRole("button", { name: "샘플 증거 승인", exact: true })
    .click();
  await expect(
    page.getByText("출고 확인 필요 10 EA는 그대로 남습니다."),
  ).toBeVisible();
  await page.getByRole("button", { name: "이전 단계" }).click();
  await expect(
    page.getByText("입고 증거 보완 후 판정 v2 예시", { exact: false }),
  ).toBeVisible();
  await stages.getByRole("button", { name: /입고 보완/ }).click();
  await page.getByRole("button", { name: "다음: 대응·마무리" }).click();
  await expect(
    page.getByRole("button", { name: "체험 요약 보기" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "샘플 작업 시작" }).click();
  await page.getByRole("button", { name: "샘플 증빙 제출" }).click();
  await expect(
    page.getByRole("button", { name: "샘플 증빙 검토 후 작업 완료" }),
  ).toBeDisabled();
  await page.getByLabel("작업 대상과 증빙이 일치하는지 확인했습니다.").check();
  await page
    .getByRole("button", { name: "샘플 증빙 검토 후 작업 완료" })
    .click();
  await page.getByRole("button", { name: "체험 요약 보기" }).click();
  await expect(
    page.getByRole("heading", { name: "체험 완료 · 실제 사건 종료 아님" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "사건 종료는 아직 할 수 없어요" }),
  ).toBeVisible();
  const other = await context.newPage();
  await other.goto("/#/demo");
  await expect(
    other.getByRole("heading", { name: "원문 확인", exact: true }),
  ).toBeVisible();
  await expect(
    other
      .getByRole("navigation", { name: "샘플 체험 단계" })
      .getByRole("button", { name: /대응·마무리/ }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "처음부터 체험" }).click();
  await page.getByRole("button", { name: "계속 체험", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "체험 완료 · 실제 사건 종료 아님" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "처음부터 체험" }).click();
  await page.getByRole("button", { name: "체험 초기화", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "원문 확인", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "다음: AI 초안" }).click();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "원문 확인", exact: true }),
  ).toBeVisible();
  expect(requests).toEqual([]);
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([]);
});
test("mobile entry preserves authentication boundary of business APIs", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("link", { name: "샘플 체험 시작 →" }).click();
  await expect(
    page.getByRole("heading", { name: "원문 확인", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "다음: AI 초안" }).click();
  await page.getByRole("button", { name: "다음: 사람 검토" }).click();
  const table = page.getByRole("region", { name: "샘플 상품 검토 표" });
  expect(await table.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(
    true,
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/wecall-demo-mobile.png",
    fullPage: true,
  });
  for (const endpoint of [
    "/api/auth/me",
    "/api/v1/recalls",
    "/api/v1/datasets",
    "/api/users",
  ])
    expect((await page.request.get(endpoint)).status()).toBe(401);
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  expect(
    (
      await page.request.post("/api/v1/recalls", {
        headers: { [csrf.headerName]: csrf.token },
        data: {
          title: "must not create",
          sourceType: "INTERNAL",
          sourceText: "blocked",
        },
      })
    ).status(),
  ).toBe(401);
  await page
    .getByRole("link", { name: "업무 화면으로 →", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "WeCall에 로그인" }),
  ).toBeVisible();
});
test("signed-in walkthrough leaves real records unchanged and restores workspace", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const before = await (
    await page.request.get("/api/v1/recalls?size=1")
  ).json();
  await page
    .getByRole("navigation", { name: "주요 메뉴" })
    .getByRole("link", { name: "샘플 체험", exact: true })
    .click();
  const mutations: string[] = [];
  page.on("request", (r) => {
    if (new URL(r.url()).pathname.startsWith("/api/") && r.method() !== "GET")
      mutations.push(r.url());
  });
  await page.getByRole("button", { name: "다음: AI 초안" }).click();
  await page.getByRole("button", { name: "다음: 사람 검토" }).click();
  await page
    .getByLabel("원문 조건과 상품의 제조사·규격을 확인했습니다.")
    .check();
  await page
    .getByRole("button", { name: "샘플 조건 승인하고 영향 보기" })
    .click();
  expect(mutations).toEqual([]);
  expect(
    await (await page.request.get("/api/v1/recalls?size=1")).json(),
  ).toEqual(before);
  await page
    .getByRole("link", { name: "업무 화면으로 →", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "회수 사건", exact: true }),
  ).toBeVisible();
});
