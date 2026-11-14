"""In-process sliding-window rate limiter for sensitive endpoints.

State lives in process memory: correct for the single-worker deployment this
project targets. Running several workers would need a shared store.
"""

import threading
import time
from collections import defaultdict, deque


class RateLimiter:
    def __init__(self, limit: int, window_seconds: float = 60.0) -> None:
        self.limit = limit
        self.window_seconds = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] >= self.window_seconds:
                hits.popleft()
            if len(hits) >= self.limit:
                return False
            hits.append(now)
            if len(self._hits) > 10_000:
                self._evict_idle(now)
            return True

    def _evict_idle(self, now: float) -> None:
        for key in [
            k for k, v in self._hits.items() if not v or now - v[-1] >= self.window_seconds
        ]:
            del self._hits[key]

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()
