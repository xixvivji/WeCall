import { test, expect } from "@playwright/test";
import { signIn } from "./support";

// Minimal synthetic PDF, used as bytes rather than a layout artifact.
function syntheticPdf() {
  const stream = "BT /F1 12 Tf 40 700 Td (Recall lot A01) Tj ET";
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
  ];
  let body = "%PDF-1.4\n";
  const offsets = [0];
  for (const [index, object] of objects.entries()) {
    offsets.push(Buffer.byteLength(body));
    body += `${index + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xref = Buffer.byteLength(body);
  body += `xref\n0 6\n0000000000 65535 f \n${offsets
    .slice(1)
    .map((n) => `${String(n).padStart(10, "0")} 00000 n \n`)
    .join("")}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body);
}

test("real PDF registration retains original, reviewed revisions and operator read access", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  await page.goto("/#/recalls");
  await page.getByRole("button", { name: "새 사건 등록" }).click();
  await page.getByLabel("사건명", { exact: true }).fill("PDF 보관 합성 검토");
  await page.getByLabel("회수 원문", { exact: true }).fill("기존 입력");
  const bytes = syntheticPdf();
  await page.getByLabel("텍스트 PDF에서 가져오기").setInputFiles({
    name: "synthetic.pdf",
    mimeType: "application/pdf",
    buffer: bytes,
  });
  await expect(page.getByLabel("PDF 추출 미리보기")).toHaveValue(
    "Recall lot A01",
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
  await page.getByRole("button", { name: "사건 등록", exact: true }).click();
  await expect(page).toHaveURL(/tab=ai/);
  await page
    .getByRole("button", { name: "원본 · 수정 이력", exact: true })
    .click();
  const detailUrl = page.url();
  await expect(
    page.getByText("제조번호 A02 회수 — 사람이 수정", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Recall lot A01", { exact: true })).toBeVisible();
  const downloading = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "synthetic.pdf 원본 다운로드" })
    .click();
  const download = await downloading;
  const chunks: Buffer[] = [];
  for await (const chunk of (await download.createReadStream())!)
    chunks.push(Buffer.from(chunk));
  expect(Buffer.concat(chunks)).toEqual(bytes);
  await page.getByRole("button", { name: "원문 수정", exact: true }).click();
  await page
    .getByLabel("수정할 원문", { exact: true })
    .fill("제조번호 A03 회수 — 재검토");
  await page
    .getByLabel("원문 수정 사유", { exact: true })
    .fill("제조번호 오타를 원문과 대조해 정정");
  await page.getByRole("button", { name: "새 원문 버전 저장" }).click();
  await expect(
    page.getByRole("heading", { name: "원문 v2", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: /^원문 v1 ·/ }).click();
  await expect(
    page.getByText("제조번호 A02 회수 — 사람이 수정", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "원문 수정", exact: true }),
  ).toHaveCount(0);
  await page.reload();
  await expect(
    page.getByText("제조번호 A03 회수 — 재검토", { exact: true }),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await signIn(page, true);
  await page.goto(detailUrl);
  await expect(
    page.getByRole("button", { name: "synthetic.pdf 원본 다운로드" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "원문 수정", exact: true }),
  ).toHaveCount(0);
});
