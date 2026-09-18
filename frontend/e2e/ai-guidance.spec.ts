import { test, expect } from "@playwright/test";
import { signIn } from "./support";
import { aiInput } from "../src/aiInput";

test("AI input counts UTF-8 and Python line boundaries without trimming source", () => {
  expect(aiInput("가".repeat(2000))).toMatchObject({
    bytes: 6000,
    exceeded: false,
  });
  expect(aiInput("가".repeat(2001)).exceeded).toBe(true);
  expect(aiInput("😀".repeat(1500))).toMatchObject({
    bytes: 6000,
    exceeded: false,
  });
  expect(aiInput("😀".repeat(1501)).exceeded).toBe(true);
  for (const separator of [
    "\n",
    "\r\n",
    "\r",
    "\v",
    "\f",
    "\x1c",
    "\x1d",
    "\x1e",
    "\x85",
    "\u2028",
    "\u2029",
  ]) {
    expect(aiInput(("a" + separator).repeat(80))).toMatchObject({
      lines: 80,
      exceeded: false,
    });
    expect(aiInput(("a" + separator).repeat(81))).toMatchObject({
      lines: 81,
      exceeded: true,
    });
  }
  expect(aiInput("")).toMatchObject({ lines: 0, empty: true });
});

test("large source remains saved; editing shows budget and failed save preserves text", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.getByRole("button", { name: "새 사건 등록" }).click();
  const source = "가".repeat(2001);
  await page
    .getByLabel("사건명", { exact: true })
    .fill("입력 한도 합성 " + Date.now());
  await page.getByLabel("회수 원문", { exact: true }).fill(source);
  await expect(page.getByLabel("AI 입력 한도")).toContainText("6,003");
  await expect(page.getByLabel("AI 입력 한도")).toContainText(
    "사건 등록·원문 보관은 가능합니다",
  );
  await page.getByRole("button", { name: "사건 등록", exact: true }).click();
  await page.getByRole("button", { name: "AI 조건 추출", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "원문 분석 요청" }),
  ).toBeDisabled();
  await page.getByRole("link", { name: "원문 확인·수정 →" }).click();
  await page.getByRole("button", { name: "원문 수정", exact: true }).click();
  await expect(page.getByLabel("수정할 원문")).toHaveValue(source);
  const updated = "제조번호 TEST-1 제품 회수";
  await page.getByLabel("수정할 원문").fill(updated);
  await page.getByLabel("원문 수정 사유").fill("합성 오류 복구 확인");
  await page.route("**/source/revisions", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ message: "합성 저장 실패" }),
    }),
  );
  await page.getByRole("button", { name: "새 원문 버전 저장" }).click();
  await expect(page.getByRole("alert")).toContainText("합성 저장 실패");
  await expect(page.getByLabel("수정할 원문")).toHaveValue(updated);
  await expect(page.getByLabel("AI 입력 한도")).toContainText(
    "입력 크기 기준으로 분석 가능합니다",
  );
});

test("failed AI jobs give distinct actions and never render upstream error text", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const response = await page.request.post("/api/v1/recalls", {
    headers: { [csrf.headerName]: csrf.token },
    data: {
      title: "AI 실패 안내 합성",
      sourceType: "INTERNAL",
      sourceText: "제조번호 TEST-1 제품 회수",
    },
  });
  expect(response.ok()).toBe(true);
  const recall = await response.json();
  let code = "LOCAL_MODEL_BUSY";
  await page.route(`**/recalls/${recall.id}/extractions`, (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "synthetic-job",
          sourceText: recall.sourceText,
          sourceVersion: 1,
          status: "FAILED",
          reviewStatus: "PENDING",
          errorCode: code,
          output: null,
        },
      ]),
    }),
  );
  for (const [errorCode, expected] of [
    ["LOCAL_MODEL_BUSY", "다른 문서를 분석 중"],
    ["MANUAL_REVIEW_REQUIRED", "예외·상품별 조건"],
    ["INVALID_AI_RESPONSE", "응답의 형식·근거"],
    ["LOCAL_MODEL_UNAVAILABLE", "모델 실행·설치 상태"],
    ["AI_TIMEOUT", "응답 시간이 초과"],
    ["private upstream body", "원문과 실행 환경을 확인"],
  ]) {
    code = errorCode!;
    await page.goto(`/#/recalls/${recall.id}?tab=ai`);
    await page.reload();
    await expect(page.getByRole("alert")).toContainText(expected!);
    await expect(page.getByText("private upstream body")).toHaveCount(0);
    await expect(
      page.getByRole("link", { name: "수동 조건 작성으로 이동 →" }),
    ).toBeVisible();
  }
});
