"""One-shot loopback capacity verification for the packaged PDF service."""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import platform
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Capacity verification does not follow redirects")


_OPENER = build_opener(RejectRedirects())


def base_url(value: str) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}
            or parsed.username or parsed.password or parsed.path not in {"", "/"}
            or parsed.query or parsed.fragment):
        raise ValueError("Capacity verification requires a loopback HTTP origin")
    return value.rstrip("/")


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise ValueError("No successful measurements")
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * quantile) - 1)], 3)


def request_body(pdf: bytes) -> tuple[bytes, str]:
    boundary = "workbench-capacity-" + hashlib.sha256(pdf).hexdigest()[:24]
    prefix = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
              'filename="capacity.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode("ascii")
    return prefix + pdf + f"\r\n--{boundary}--\r\n".encode("ascii"), boundary


def probe(origin: str, body: bytes, boundary: str, barrier: threading.Barrier | None = None) -> dict:
    if barrier is not None:
        barrier.wait(timeout=10)
    request = Request(origin + "/api/v1/pdf/info", data=body,
                      headers={"Content-Type": "multipart/form-data; boundary=" + boundary}, method="POST")
    started = time.perf_counter()
    try:
        response = _OPENER.open(request, timeout=120)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read(1048577)
        if len(payload) > 1048576:
            raise ValueError("Probe response exceeds expected metadata size")
        result = json.loads(payload)
        status = response.status
        if status == 200:
            if not isinstance(result, dict) or not isinstance(result.get("pageCount"), int) or result["pageCount"] < 1:
                raise ValueError("Successful response is not PDF metadata")
        elif status == 429:
            if result.get("code") != "CAPACITY_EXCEEDED" or result.get("status") != 429:
                raise ValueError("Unexpected capacity rejection")
        else:
            raise ValueError(f"Unexpected probe HTTP status: {status}")
    return {"status": status, "elapsed_ms": round((time.perf_counter() - started) * 1000, 3)}


def verify(origin: str, pdf: bytes, samples: int, parallel: int, rounds: int) -> dict:
    origin = base_url(origin)
    if not 1 <= samples <= 100 or not 2 <= parallel <= 8 or not 1 <= rounds <= 10:
        raise ValueError("Invalid bounded probe counts")
    if not pdf.startswith(b"%PDF-") or len(pdf) > 52428800:
        raise ValueError("A PDF fixture of at most 50 MiB is required")
    body, boundary = request_body(pdf)
    if probe(origin, body, boundary)["status"] != 200:
        raise ValueError("Warm-up request was rejected")
    sequential = [probe(origin, body, boundary) for _ in range(samples)]
    if any(item["status"] != 200 for item in sequential):
        raise ValueError("Sequential request was rejected")
    bursts = []
    for _ in range(rounds):
        barrier = threading.Barrier(parallel)
        with ThreadPoolExecutor(max_workers=parallel) as pool:
            futures = [pool.submit(probe, origin, body, boundary, barrier) for _ in range(parallel)]
            burst = [future.result() for future in futures]
        if not any(item["status"] == 200 for item in burst):
            raise ValueError("No burst request succeeded")
        bursts.append(burst)
    recovery = probe(origin, body, boundary)
    if recovery["status"] != 200:
        raise ValueError("Service did not recover after concurrent requests")
    with _OPENER.open(origin + "/v3/api-docs", timeout=10) as response:
        if response.status != 200 or not json.load(response).get("openapi"):
            raise ValueError("OpenAPI readiness failed after probes")
    elapsed = [item["elapsed_ms"] for item in sequential]
    return {
        "format": "pdf-workbench-capacity-v1", "status": "PASS",
        "measured_utc": datetime.now(timezone.utc).isoformat(), "origin": origin,
        "platform": platform.system(), "machine": platform.machine(),
        "operation": "POST /api/v1/pdf/info", "fixture_bytes": len(pdf),
        "fixture_sha256": hashlib.sha256(pdf).hexdigest(),
        "warmup_requests": 1, "sequential": sequential,
        "sequential_p50_ms": percentile(elapsed, .50), "sequential_p95_ms": percentile(elapsed, .95),
        "parallel": parallel, "bursts": bursts,
        "burst_status_counts": dict(Counter(str(item["status"]) for burst in bursts for item in burst)),
        "recovery": recovery, "openapi_after_probes": True,
        "scope": "Loopback metadata extraction baseline; not a public-service throughput estimate",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=10)
    parser.add_argument("--parallel", type=int, default=4)
    parser.add_argument("--rounds", type=int, default=2)
    args = parser.parse_args()
    if args.fixture.stat().st_size > 52428800:
        parser.error("Fixture exceeds 50 MiB")
    report = verify(args.base_url, args.fixture.read_bytes(), args.samples, args.parallel, args.rounds)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "status", "sequential_p50_ms", "sequential_p95_ms", "burst_status_counts", "openapi_after_probes")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
