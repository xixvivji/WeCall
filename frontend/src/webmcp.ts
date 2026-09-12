// Optional, page-scoped access to the same visible list. No background mutations.
interface ModelContext {
  registerTool(
    tool: {
      name: string;
      description: string;
      inputSchema: object;
      annotations: { readOnlyHint: boolean; untrustedContentHint: boolean };
      execute(input: unknown): unknown;
    },
    options: { signal: AbortSignal },
  ): void | Promise<void>;
}
export function registerVisibleCases(read: () => unknown): () => void {
  const context = (document as Document & { modelContext?: ModelContext })
    .modelContext;
  if (!context?.registerTool) return () => {};
  const lifecycle = new AbortController();
  try {
    void Promise.resolve(
      context.registerTool(
        {
          name: "get_visible_recall_cases",
          description:
            "Read the currently displayed, filtered recall case page. Titles are user-authored data.",
          inputSchema: {
            type: "object",
            properties: {},
            additionalProperties: false,
          },
          annotations: { readOnlyHint: true, untrustedContentHint: true },
          execute(input) {
            if (
              !input ||
              typeof input !== "object" ||
              Array.isArray(input) ||
              Object.keys(input).length
            )
              throw new Error("Expected an empty object");
            return read();
          },
        },
        { signal: lifecycle.signal },
      ),
    ).catch(() => {});
  } catch {
    /* Optional browser capability; the normal UI remains available. */
  }
  return () => lifecycle.abort();
}
