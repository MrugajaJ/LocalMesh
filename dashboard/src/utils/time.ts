// Simple time utility — no heavy date library needed
export function formatDistanceToNow(isoString: string): string {
  const diff = Date.now() - new Date(isoString).getTime();
  const mins  = Math.floor(diff / 60_000);
  const hours = Math.floor(diff / 3_600_000);
  if (hours > 0)  return `${hours}h`;
  if (mins > 0)   return `${mins}m`;
  return 'just now';
}

export function formatTime(isoString: string): string {
  return new Date(isoString).toLocaleTimeString('en-US', {
    hour:   '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}
