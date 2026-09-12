import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
const sample = resolve("../samples/recall-001");
// No credentials, traces, or login screenshots are persisted in test artifacts.
const credentials: Record<string, string> = { ...process.env } as Record<
  string,
  string
>;
for (const line of readFileSync(resolve("../.env"), "utf8").split("\n")) {
  if (line && !line.startsWith("#") && line.includes("=")) {
    const split = line.indexOf("=");
    const key = line.slice(0, split);
    if (!credentials[key]) credentials[key] = line.slice(split + 1);
  }
}
async function signIn(page: Page, operator = false) {
  await page
    .getByLabel("계정명", { exact: true })
    .fill(
      operator
        ? credentials.WECALL_BOOTSTRAP_OPERATOR_USERNAME!
        : credentials.WECALL_USERNAME!,
    );
  await page
    .getByLabel("비밀번호", { exact: true })
    .fill(
      operator
        ? credentials.WECALL_BOOTSTRAP_OPERATOR_PASSWORD!
        : credentials.WECALL_PASSWORD!,
    );
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeAttached();
}

test("real backend workflow and role boundaries", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await signIn(page);
  await page.getByRole("link", { name: "데이터 관리", exact: true }).click();
  await page.getByLabel("데이터 기준 시각").fill("2026-09-09T18:00");
  for (const [label, file] of [
    ["상품 CSV", "products"],
    ["입고 CSV", "receipts"],
    ["재고 CSV", "inventory"],
    ["출고 CSV", "shipments"],
    ["출고·입고 연결 CSV", "shipment_allocations"],
  ])
    await page
      .getByLabel(label!, { exact: true })
      .setInputFiles(resolve(sample, file + ".csv"));
  await page.getByRole("button", { name: "데이터 검증 후 등록" }).click();
  await expect(page.getByRole("status")).toContainText("등록 완료");
  await page.getByRole("link", { name: "준비 상태 점검" }).first().click();
  await expect(
    page.getByRole("heading", { name: "데이터 준비 상태" }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "R4", exact: true }),
  ).toBeVisible();
  await page.getByLabel("점검 항목", { exact: true }).selectOption("shipments");
  await expect(
    page.getByRole("cell", { name: "S2", exact: true }),
  ).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/wecall-readiness-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole("link", { name: "회수 사건", exact: true }).click();
  const title = "브라우저 검증 " + Date.now();
  await page.getByRole("button", { name: "새 사건 등록" }).click();
  await page.getByLabel("사건명", { exact: true }).fill(title);
  await page
    .getByLabel("회수 원문")
    .fill(readFileSync(resolve(sample, "notice.md"), "utf8"));
  await page.getByRole("button", { name: "사건 등록", exact: true }).click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  await page.getByRole("button", { name: "새 조건 작성" }).click();
  await page.getByLabel("판정에 사용할 데이터 버전").selectOption({ index: 1 });
  await page
    .getByLabel("원문 근거 인용")
    .fill("제조번호 A01 또는 A02이면서 소비기한이 2026-10-31인 제품");
  await page
    .getByLabel("조건 연산", { exact: true })
    .first()
    .selectOption("AND");
  await page.getByLabel("조건 연산", { exact: true }).nth(1).selectOption("IN");
  await page.getByLabel("조건 값", { exact: true }).fill("A01,A02");
  await page.getByRole("button", { name: "하위 조건 추가" }).click();
  await page
    .getByLabel("조건 항목", { exact: true })
    .nth(1)
    .selectOption("EXPIRY_DATE");
  await page.getByLabel("조건 값", { exact: true }).nth(1).fill("2026-10-31");
  for (const id of ["P1", "P2", "P3"]) {
    await page
      .getByLabel(id + " 연결 검토", { exact: true })
      .selectOption(id === "P1" ? "MATCHED" : "EXCLUDED");
    await page
      .getByLabel(id + " 검토 근거", { exact: true })
      .fill(id === "P1" ? "제조사·규격 확인" : "별도 규격·제조사 확인");
  }
  await page.getByRole("button", { name: "조건 초안 저장" }).click();
  await expect(
    page.getByText("조건을 승인했습니다.", { exact: false }),
  ).not.toBeVisible();
  await expect(
    page.getByRole("button", { name: "이 조건으로 판정 실행" }),
  ).not.toBeVisible();
  await page
    .getByLabel("원문, 데이터 버전, 상품 연결과 조건을 확인했습니다.")
    .check();
  await page.getByRole("button", { name: "조건 승인", exact: true }).click();
  await page.getByRole("button", { name: "이 조건으로 판정 실행" }).click();
  await expect(page.locator(".metric strong")).toHaveText([
    "110",
    "110",
    "30",
    "60",
    "10",
    "20",
  ]);
  await expect(
    page
      .getByRole("row")
      .filter({ has: page.getByRole("cell", { name: "S2", exact: true }) }),
  ).toContainText("10");
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "/tmp/wecall-impact-desktop.png",
    fullPage: true,
  });
  const downloaded = page.waitForEvent("download");
  await page.getByRole("button", { name: "출고 CSV 다운로드" }).click();
  const csvDownload = await downloaded;
  expect(csvDownload.suggestedFilename()).toMatch(
    /^assessment-.*-shipments\.csv$/,
  );
  const csvStream = await csvDownload.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of csvStream!) chunks.push(Buffer.from(chunk));
  expect(Buffer.concat(chunks).toString("utf8")).toContain(
    "needs_review_ea,unlinked_ea",
  );
  const oldRun = await page.getByLabel("조회·작업 기준 판정").inputValue();
  await page.getByRole("button", { name: "입고 증거", exact: true }).click();
  const evidence = JSON.parse(
    readFileSync(resolve(sample, "evidence-request.json"), "utf8"),
  );
  async function proposeEvidence(amount: string) {
    await page
      .getByRole("button", { name: "입고 증거 등록", exact: true })
      .click();
    await page.getByLabel("보완할 입고", { exact: true }).selectOption("R4");
    await page.getByLabel("입고 증거 원문").fill(evidence.documentText);
    await page.getByLabel("증거 근거 인용").fill(evidence.sourceQuote);
    await page.getByLabel("문서의 상품 ID").fill("P1");
    await page.getByLabel("문서의 전체 입고 수량").fill(amount);
    await page.getByLabel("문서의 입고일").fill("2026-09-04");
    await page.getByLabel("보완 제조번호").fill("A02");
    await page.getByLabel("보완 소비기한").fill("2026-10-31");
    await page.getByRole("button", { name: "증거 제안 저장" }).click();
    await expect(
      page.getByRole("heading", { name: "R4 증거 검토" }),
    ).toBeVisible();
  }
  await proposeEvidence("39");
  await expect(
    page.getByRole("button", { name: "증거 승인 및 재판정" }),
  ).toBeDisabled();
  await page.getByLabel("입고 증거 검토 사유").fill("입고 수량 불일치 확인");
  await page.getByRole("button", { name: "증거 반려", exact: true }).click();
  await expect(
    page.getByText("증거를 반려했습니다.", { exact: true }),
  ).toBeVisible();
  await proposeEvidence("40");
  await page
    .getByLabel(
      "입고 건·상품·전체 수량·입고일이 일치하고 단일 제조분임을 증거로 확인했습니다.",
    )
    .check();
  await page
    .getByLabel("입고 증거 검토 사유")
    .fill("합성 문서의 전체 40 EA 단일 제조분 확인");
  await page.getByRole("button", { name: "증거 승인 및 재판정" }).click();
  await page.getByRole("button", { name: "보완 후 판정 보기" }).click();
  await expect(page.locator(".metric strong")).toHaveText([
    "140",
    "110",
    "0",
    "70",
    "10",
    "10",
  ]);
  const newRun = await page.getByLabel("조회·작업 기준 판정").inputValue();
  await page.getByLabel("조회·작업 기준 판정").selectOption(oldRun);
  await expect(page.locator(".metric strong")).toHaveText([
    "110",
    "110",
    "30",
    "60",
    "10",
    "20",
  ]);
  await page.getByLabel("조회·작업 기준 판정").selectOption(newRun);
  await expect(page.locator(".metric strong")).toHaveText([
    "140",
    "110",
    "0",
    "70",
    "10",
    "10",
  ]);
  await page.getByRole("button", { name: "대응 작업", exact: true }).click();
  await page.getByRole("button", { name: "작업 만들기" }).click();
  await page.getByLabel("작업 제목").fill("합성 재고 격리 확인");
  await page
    .getByLabel("담당자", { exact: true })
    .selectOption(credentials.WECALL_BOOTSTRAP_OPERATOR_USERNAME!);
  await page
    .getByLabel("작업 지시")
    .fill("합성 시연용 작업. 실제 조치 기록이 아닙니다.");
  await page.getByRole("button", { name: "작업 등록", exact: true }).click();
  await page.getByRole("button", { name: "작업 상세" }).click();
  await page.getByLabel("상태 변경 사유").fill("가상 작업 시작");
  await page.getByRole("button", { name: "작업 시작", exact: true }).click();
  await page
    .getByLabel("처리 증빙", { exact: true })
    .fill("합성 데이터 시연: 재고 격리 확인. 실제 작업 아님.");
  await page.getByRole("button", { name: "증빙 제출" }).click();
  await page.getByLabel("증빙 검토 사유").fill("합성 근거 검토");
  await page.getByRole("button", { name: "증빙 승인", exact: true }).click();
  await page.getByLabel("상태 변경 사유").fill("시연 완료");
  await page.getByRole("button", { name: "검토 후 작업 완료" }).click();
  await expect(page.getByRole("button", { name: "작업 재개" })).toBeVisible();
  await page.getByRole("button", { name: "종료 점검", exact: true }).click();
  await expect(
    page.getByText("확인 필요 출고 수량이 남아 있습니다", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "최종 검토 후 사건 종료" }),
  ).toBeDisabled();
  await page.getByRole("link", { name: "← 사건 목록" }).click();
  await page.getByLabel("사건명 검색").fill(title);
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page.getByRole("link", { name: title, exact: true }).click();
  await page.getByRole("button", { name: "로그아웃" }).click();
  await signIn(page, true);
  await expect(
    page.getByRole("button", { name: "새 조건 작성" }),
  ).not.toBeVisible();
  await page.getByRole("button", { name: "대응 작업", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "작업 만들기" }),
  ).not.toBeVisible();
  await page.getByRole("button", { name: "작업 상세" }).click();
  await expect(
    page.getByRole("button", { name: "작업 재개" }),
  ).not.toBeVisible();
  await page.getByRole("link", { name: "← 사건 목록" }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole("heading", { name: "회수 사건", exact: true }),
  ).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.screenshot({
    path: "/tmp/wecall-cases-mobile.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("invalid login and session expiry return to login", async ({
  page,
  context,
}) => {
  await page.goto("/");
  await page.getByLabel("계정명", { exact: true }).fill("unknown-account");
  await page
    .getByLabel("비밀번호", { exact: true })
    .fill("invalid-not-a-real-password");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("계정명 또는 비밀번호");
  await signIn(page);
  await page.getByRole("link", { name: "회수 사건", exact: true }).click();
  await context.clearCookies();
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "WeCall에 로그인" }),
  ).toBeVisible();
});

test("reviewer creates account; operator cannot open account controls", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.getByRole("link", { name: "계정 관리", exact: true }).click();
  const username = "e2e-" + Date.now();
  await page.getByLabel("새 계정명").fill(username);
  await page.getByLabel("표시 이름").fill("합성 테스트 담당자");
  await page
    .getByLabel("초기 비밀번호")
    .fill("Temporary-" + crypto.randomUUID());
  await page.getByRole("button", { name: "계정 생성", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("계정을 생성했습니다.");
  await expect(page.getByLabel("초기 비밀번호")).toHaveValue("");
  await expect(
    page.getByRole("cell", { name: username, exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await signIn(page, true);
  await expect(
    page.getByRole("link", { name: "계정 관리", exact: true }),
  ).not.toBeVisible();
  await expect(
    page.getByText("계정 관리는 검토자만 사용할 수 있습니다."),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "계정 생성", exact: true }),
  ).not.toBeVisible();
});

test("password change and account status revoke other sessions", async ({
  page,
  browser,
}) => {
  const username = "security-" + Date.now();
  const oldPassword = "Initial-" + crypto.randomUUID();
  const newPassword = "Changed-" + crypto.randomUUID();
  await page.goto("/");
  await signIn(page);
  await page.getByRole("link", { name: "계정 관리", exact: true }).click();
  await page.getByLabel("새 계정명").fill(username);
  await page.getByLabel("표시 이름").fill("보안 흐름 검증 계정");
  await page.getByLabel("초기 비밀번호").fill(oldPassword);
  await page.getByRole("button", { name: "계정 생성", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: username, exact: true }),
  ).toBeVisible();
  const first = await browser.newContext(),
    second = await browser.newContext();
  try {
    const a = await first.newPage(),
      b = await second.newPage();
    async function enter(p: Page, password: string) {
      await p.goto("http://127.0.0.1:5173/");
      await p.getByLabel("계정명", { exact: true }).fill(username);
      await p.getByLabel("비밀번호", { exact: true }).fill(password);
      await p.getByRole("button", { name: "로그인", exact: true }).click();
      await expect(
        p.getByRole("button", { name: "로그아웃", exact: true }),
      ).toBeVisible();
    }
    await enter(a, oldPassword);
    await enter(b, oldPassword);
    await a.getByRole("link", { name: "내 계정", exact: true }).click();
    await a.screenshot({ path: "/tmp/wecall-account-security.png" });
    await a.getByLabel("현재 비밀번호", { exact: true }).fill(oldPassword);
    await a.getByLabel("새 비밀번호", { exact: true }).fill(newPassword);
    await a.getByLabel("새 비밀번호 확인", { exact: true }).fill(newPassword);
    await a.getByRole("button", { name: "비밀번호 변경 후 로그아웃" }).click();
    await expect(
      a.getByRole("heading", { name: "WeCall에 로그인" }),
    ).toBeVisible();
    await expect(a.getByRole("status")).toContainText("새 비밀번호로 로그인");
    await b.getByRole("button", { name: "검색", exact: true }).click();
    await expect(
      b.getByRole("heading", { name: "WeCall에 로그인" }),
    ).toBeVisible();
    await enter(b, newPassword);
    async function status(reason: string, button: string) {
      await page
        .getByRole("row")
        .filter({
          has: page.getByRole("cell", { name: username, exact: true }),
        })
        .getByRole("button", { name: "상태 · 이력" })
        .click();
      await page.getByLabel("계정 상태 변경 사유").fill(reason);
      await page.getByRole("button", { name: button, exact: true }).click();
      await expect(
        page.getByRole("heading", { name: username + " 계정 상태" }),
      ).not.toBeVisible();
    }
    await page.reload();
    await status("합성 계정 접근 중지 검증", "계정 비활성화");
    await expect(page.getByRole("status")).toContainText(
      "계정을 비활성화했습니다.",
    );
    await b.getByRole("button", { name: "검색", exact: true }).click();
    await expect(
      b.getByRole("heading", { name: "WeCall에 로그인" }),
    ).toBeVisible();
    await status("합성 계정 재활성 검증", "계정 재활성화");
    await enter(b, newPassword);
    await page
      .getByRole("row")
      .filter({ has: page.getByRole("cell", { name: username, exact: true }) })
      .getByRole("button", { name: "상태 · 이력" })
      .click();
    await expect(
      page.getByText("본인 비밀번호 변경", { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText("합성 계정 접근 중지 검증", { exact: true }),
    ).toBeVisible();
  } finally {
    await first.close();
    await second.close();
  }
});

test("workspace filters tasks and opens their existing detail", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.getByRole("link", { name: "업무 대시보드", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "업무 대시보드", exact: true }),
  ).toBeVisible();
  await page.getByLabel("작업 범위").selectOption("all");
  await page.getByLabel("작업 상태", { exact: true }).selectOption("COMPLETED");
  await page.getByLabel("사건·작업 검색").fill("합성 재고 격리 확인");
  await page.getByRole("button", { name: "작업 조회" }).click();
  await page
    .getByRole("link", { name: "작업 열기", exact: true })
    .first()
    .click();
  await expect(page.getByRole("button", { name: "상세 닫기" })).toBeVisible();
  await expect(page.getByRole("button", { name: "작업 재개" })).toBeVisible();
  await page.getByRole("link", { name: "업무 대시보드", exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("button", { name: "작업 조회" })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/wecall-workspace-mobile.png",
    fullPage: true,
  });
});
