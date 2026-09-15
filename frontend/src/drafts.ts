import { computed, onScopeDispose, ref } from "vue";
import { user } from "./api";
const guards = new Set<() => boolean>();
const message =
  "저장하지 않은 입력이나 선택한 파일이 있습니다. 입력을 버리고 이동할까요?";
function snapshot(value: unknown) {
  return JSON.stringify(value, (_key, item) =>
    item instanceof File
      ? {
          name: item.name,
          size: item.size,
          modified: item.lastModified,
          type: item.type,
        }
      : item,
  );
}
export function hasDrafts() {
  return !!user.value && [...guards].some((check) => check());
}
export function confirmDrafts() {
  return !hasDrafts() || window.confirm(message);
}
export function useDraftGuard(read: () => unknown) {
  const baseline = ref(snapshot(read()));
  const dirty = computed(() => snapshot(read()) !== baseline.value);
  const check = () => dirty.value;
  guards.add(check);
  onScopeDispose(() => guards.delete(check));
  return {
    dirty,
    saved: () => {
      baseline.value = snapshot(read());
    },
    discard: () => !dirty.value || window.confirm(message),
  };
}
window.addEventListener("beforeunload", (event) => {
  if (hasDrafts()) {
    event.preventDefault();
    event.returnValue = "";
  }
});
