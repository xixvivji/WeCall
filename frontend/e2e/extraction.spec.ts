import { test, expect } from "@playwright/test";
import { signIn } from "./support";

test("AI draft requires human product review and preserves edits; operators cannot request or convert", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const response = await page.request.post("/api/v1/recalls", {
    headers: { [csrf.headerName]: csrf.token },
    data: {
      title: "AI 화면 합성 " + Date.now(),
      sourceType: "INTERNAL",
      sourceText: "제조번호 K01 회수",
    },
  });
  expect(response.ok()).toBe(true);
  const recall = await response.json();
  const job: any = {
    id: "synthetic-job",
    sourceText: "제조번호 K01 회수",
    status: "SUCCEEDED",
    reviewStatus: "PENDING",
    errorCode: null,
    conditionId: null,
    reviewNote: null,
    output: {
      mode: "LIVE",
      provider: "ollama-local",
      model: "synthetic-model-contract",
      rule: { op: "EQ", field: "LOT_NUMBER", values: ["K01"] },
      sourceQuote: "제조번호 K01 회수",
      warnings: ["합성 UI 검증 응답. 실제 모델 평가와 별도."],
    },
  };
  let created = false,
    failed = true,
    conversion: any;
  const endpoint = `**/api/v1/recalls/${recall.id}/extractions`;
  await page.route(endpoint, (route) => {
    if (failed) return route.abort();
    if (route.request().method() === "POST") {
      created = true;
      return route.fulfill({ status: 202, json: job });
    }
    return route.fulfill({ json: created ? [job] : [] });
  });
  await page.route(endpoint + "/synthetic-job/condition", (route) => {
    conversion = route.request().postDataJSON();
    job.reviewStatus = "ACCEPTED";
    job.conditionId = "reviewed-condition";
    job.reviewNote = conversion.note;
    return route.fulfill({ status: 201, json: job });
  });
  await page.route("**/api/v1/datasets?*", (route) =>
    route.fulfill({
      json: {
        items: [{ id: "synthetic-dataset", asOf: "2026-09-09T09:00:00Z" }],
        totalPages: 1,
        totalElements: 1,
      },
    }),
  );
  await page.route("**/api/v1/datasets/synthetic-dataset/products?*", (route) =>
    route.fulfill({
      json: {
        items: [
          {
            id: "P1",
            name: "합성 상품",
            manufacturer: "가상 제조사",
            packSize: "100g",
          },
        ],
        totalPages: 1,
        totalElements: 1,
      },
    }),
  );
  await page.goto(`/#/recalls/${recall.id}?tab=ai`);
  await expect(
    page.getByRole("button", { name: "원문 분석 요청" }),
  ).toBeDisabled();
  failed = false;
  await page.getByRole("button", { name: "분석 이력 다시 조회" }).click();
  await page.getByRole("button", { name: "원문 분석 요청" }).click();
  await expect(
    page.getByText("실제 모델 응답", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByText("제조번호 K01 일치", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "조건과 상품 연결 검토" }).click();
  await expect(page.getByLabel("원문 근거 인용")).toHaveValue(
    "제조번호 K01 회수",
  );
  await expect(
    page.getByRole("button", { name: "조건 초안 저장" }),
  ).toBeDisabled();
  await page
    .getByLabel("AI 검토 사유")
    .fill("합성 원문과 상품 규격을 직접 검토");
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "영향 조회", exact: true }).click();
  await expect(page).toHaveURL(/tab=ai/);
  await page
    .getByLabel("판정에 사용할 데이터 버전")
    .selectOption("synthetic-dataset");
  await page.getByLabel("P1 연결 검토").selectOption("MATCHED");
  await page.getByLabel("P1 검토 근거").fill("상품명과 규격 확인");
  await page.getByRole("button", { name: "조건 초안 저장" }).click();
  await expect(
    page.getByText("조건 초안을 만들었습니다.", { exact: false }),
  ).toBeVisible();
  expect(conversion.definition.productReviews.P1.status).toBe("MATCHED");
  expect(conversion.definition.rule.values).toEqual(["K01"]);
  expect(conversion.note).toContain("직접 검토");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({ path: "/tmp/wecall-ai-mobile.png", fullPage: true });
  await page
    .getByRole("button", { name: "로그아웃" })
    .filter({ visible: true })
    .click();
  await signIn(page, true);
  await page.goto(`/#/recalls/${recall.id}?tab=ai`);
  await expect(
    page.getByText("분석 요청과 검토는 검토자 권한으로 진행합니다."),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "원문 분석 요청" }),
  ).toHaveCount(0);
});
