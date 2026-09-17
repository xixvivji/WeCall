import { test, expect } from "@playwright/test";
import { signIn } from "./support";

test("PDF preview preserves existing input and saves reviewed text", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.goto("/#/recalls");
  await page.getByRole("button", { name: "새 사건 등록" }).click();
  await page.getByLabel("사건명", { exact: true }).fill("PDF 합성 검토");
  await page.getByLabel("회수 원문", { exact: true }).fill("기존 입력");
  await page.route("**/api/v1/source-pdf", (route) =>
    route.fulfill({
      json: { text: "제조번호 A01 회수", pages: 1, scanStatus: "NOT_SCANNED" },
    }),
  );
  await page
    .getByLabel("텍스트 PDF에서 가져오기")
    .setInputFiles({
      name: "synthetic.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("synthetic UI contract"),
    });
  await expect(page.getByLabel("PDF 추출 미리보기")).toHaveValue(
    "제조번호 A01 회수",
  );
  await expect(page.getByLabel("회수 원문", { exact: true })).toHaveValue(
    "기존 입력",
  );
  await expect(
    page.getByRole("button", { name: "사건 등록", exact: true }),
  ).toBeDisabled();
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "추출 내용으로 원문 채우기" }).click();
  await page
    .getByLabel("회수 원문", { exact: true })
    .fill("제조번호 A02 회수 — 사람이 수정");
  const request = page.waitForRequest(
    (req) => req.url().endsWith("/api/v1/recalls") && req.method() === "POST",
  );
  await page.getByRole("button", { name: "사건 등록", exact: true }).click();
  expect((await request).postDataJSON().sourceText).toBe(
    "제조번호 A02 회수 — 사람이 수정",
  );
  await expect(page).toHaveURL(/tab=ai/);
});
