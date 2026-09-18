export function aiInput(text: string) {
  const parts = text.split(/\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]/);
  const lines = text ? parts.length - (parts.at(-1) === "" ? 1 : 0) : 0;
  const bytes = new TextEncoder().encode(text).length;
  return {
    bytes,
    lines,
    empty: !text.trim(),
    exceeded: bytes > 6000 || lines > 80,
  };
}
