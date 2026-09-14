import { expect, type Page } from "@playwright/test";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
// No credentials, traces, or login screenshots are persisted in test artifacts.
export const credentials: Record<string, string> = { ...process.env } as Record<
  string,
  string
>;
const envFile = resolve("../.env");
for (const line of (existsSync(envFile)
  ? readFileSync(envFile, "utf8")
  : ""
).split("\n")) {
  if (line && !line.startsWith("#") && line.includes("=")) {
    const split = line.indexOf("=");
    const key = line.slice(0, split);
    if (!credentials[key]) credentials[key] = line.slice(split + 1);
  }
}
for (const key of [
  "WECALL_USERNAME",
  "WECALL_PASSWORD",
  "WECALL_BOOTSTRAP_OPERATOR_USERNAME",
  "WECALL_BOOTSTRAP_OPERATOR_PASSWORD",
]) {
  if (!credentials[key])
    throw new Error(`브라우저 검증 환경 변수 누락: ${key}`);
}
export async function signIn(page: Page, operator = false) {
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
