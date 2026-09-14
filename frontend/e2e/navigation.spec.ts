import { test, expect } from "@playwright/test";
import { signIn } from "./support";
test("case tabs survive reload and browser history, and invalid run links remain explicit", async ({
  page,
}) => {
  await page.goto("/");
  await signIn(page);
  const csrf = await (await page.request.get("/api/auth/csrf")).json();
  const response = await page.request.post("/api/v1/recalls", {
    headers: { [csrf.headerName]: csrf.token },
    data: {
      title: "화면 이동 합성 " + Date.now(),
      sourceType: "INTERNAL",
      sourceText: "합성 원문",
    },
  });
  expect(response.ok()).toBe(true);
  const recall = await response.json();
  await page.goto(`/#/recalls/${recall.id}`);
  await page.getByRole("button", { name: "대응 보고서", exact: true }).click();
  await expect(page).toHaveURL(/tab=report/);
  await expect(
    page.getByRole("region", { name: "사건 대응 보고서" }),
  ).toContainText("기준 판정이 선택되지 않았습니다.");
  await page.reload();
  await expect(
    page.getByRole("button", { name: "대응 보고서", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page.getByRole("button", { name: "업무 이력", exact: true }).click();
  await expect(page).toHaveURL(/tab=history/);
  await page.goBack();
  await expect(
    page.getByRole("button", { name: "대응 보고서", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page.goForward();
  await expect(
    page.getByRole("button", { name: "업무 이력", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page.evaluate(() =>
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText: async (value: string) => {
          document.documentElement.dataset.copiedLink = value;
        },
      },
    }),
  );
  await page.getByRole("button", { name: "현재 화면 링크 복사" }).click();
  await expect(page.getByRole("status")).toContainText("링크를 복사했습니다");
  const copied = await page.locator("html").getAttribute("data-copied-link");
  expect(copied).toContain("tab=history");
  await page.goto(copied!);
  await expect(
    page.getByRole("button", { name: "업무 이력", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page.goto(
    `/#/recalls/${recall.id}?tab=impact&assessment=00000000-0000-0000-0000-000000000001`,
  );
  await expect(page.getByRole("alert")).toContainText("찾을 수 없는 기준 판정");
  await expect(
    page.getByRole("button", { name: "재고 CSV 다운로드" }),
  ).toHaveCount(0);
  await page.getByLabel("조회·작업 기준 판정").selectOption("");
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
