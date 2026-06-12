export function todayDate() {
  return new Date().toISOString().split("T")[0];
}
