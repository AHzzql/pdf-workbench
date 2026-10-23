"""Bounded loopback verification of large PDF inputs and mixed operations."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from io import BytesIO
import argparse
import hashlib
import json
from pathlib import Path
import threading
import time
from urllib.error import HTTPError

from pypdf import PdfReader, PdfWriter
from reportlab.pdfgen.canvas import Canvas
from check_pdf_capacity import _OPENER, base_url, request_body


def fixture(pages: int, attachment_bytes: int = 0) -> bytes:
    if not 1 <= pages <= 201 or not 0 <= attachment_bytes <= 51 * 1024 * 1024:
        raise ValueError("Invalid fixture limits")
    output = BytesIO()
    canvas = Canvas(output, pagesize=(595, 842), pageCompression=0, invariant=1)
    canvas.setTitle("Reproducible PDF capacity fixture")
    for page in range(1, pages + 1):
        canvas.setFont("Helvetica", 10)
        canvas.drawString(40, 800, f"CAPACITY_PAGE_{page:04d}")
        for row in range(12):
            canvas.drawString(40, 775 - row * 15, f"Row {row:02d}: text extraction, geometry, indexing and PDF processing verification.")
        canvas.showPage()
    canvas.save()
    data = output.getvalue()
    if attachment_bytes:
        writer = PdfWriter(clone_from=BytesIO(data))
        writer.add_attachment("capacity-payload.bin", b"\x00" * attachment_bytes)
        output = BytesIO()
        writer.write(output)
        data = output.getvalue()
    return data


def multipart(pdf: bytes, fields: dict) -> tuple[bytes, str]:
    body, boundary = request_body(pdf)
    suffix = f"--{boundary}--\r\n".encode("ascii")
    if not body.endswith(suffix):
        raise ValueError("Invalid multipart fixture")
    chunks = [body[:-len(suffix)]]
    for name, value in fields.items():
        if not name.isidentifier():
            raise ValueError("Invalid field name")
        chunks.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n').encode("utf-8"))
    return b"".join(chunks) + suffix, boundary


def post(origin: str, operation: str, pdf: bytes, fields: dict,
         barrier: threading.Barrier | None = None) -> tuple[dict, bytes]:
    from urllib.request import Request
    body, boundary = multipart(pdf, fields)
    if barrier is not None:
        barrier.wait(timeout=15)
    started = time.perf_counter()
    request = Request(origin + "/api/v1/pdf/" + operation, data=body, method="POST",
                      headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    try:
        response = _OPENER.open(request, timeout=120)
    except HTTPError as error:
        response = error
    with response:
        payload = BytesIO()
        while chunk := response.read(65536):
            if payload.tell() + len(chunk) > 209715200:
                raise ValueError("Response exceeds configured 200 MiB output limit")
            payload.write(chunk)
        data = payload.getvalue()
        result = {"operation": operation, "status": response.status, "response_bytes": len(data),
                  "elapsed_ms": round((time.perf_counter() - started) * 1000, 3)}
        if response.status != 200:
            result["code"] = json.loads(data).get("code")
    return result, data


def require(result: dict, status: int, code: str | None = None) -> None:
    if result["status"] != status or (code is not None and result.get("code") != code):
        raise AssertionError(f"{result['operation']}: unexpected HTTP {result['status']} {result.get('code', '')}")


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def validate(operation: str, data: bytes, pages: int) -> None:
    if operation == "info":
        expect(json.loads(data)["pageCount"] == pages, "Metadata page count differs")
    elif operation == "text":
        values = json.loads(data)["pages"]
        expect(len(values) == pages, "Text result page count differs")
        expect("CAPACITY_PAGE_0001" in values[0]["text"], "First text page marker is missing")
        expect(f"CAPACITY_PAGE_{pages:04d}" in values[-1]["text"], "Last text page marker is missing")
    elif operation == "text/search":
        matches = json.loads(data)
        expect(len(matches) == pages, "Search match count differs")
        expect({match["page"] for match in matches} == set(range(1, pages + 1)), "Search page coverage differs")
    else:
        reader = PdfReader(BytesIO(data))
        if operation == "encrypt":
            expect(reader.is_encrypted, "Output PDF is not encrypted")
            expect(reader.decrypt("capacity-reader") != 0, "Output PDF password did not open it")
        expect(len(reader.pages) == pages, "Output PDF page count differs")
        if operation == "watermark":
            expect("CAPACITY_WATERMARK" in reader.pages[0].extract_text(), "First page watermark is missing")
            expect("CAPACITY_WATERMARK" in reader.pages[-1].extract_text(), "Last page watermark is missing")


def verify(origin: str, directory: Path, report: dict) -> None:
    origin = base_url(origin)
    directory.mkdir(parents=True, exist_ok=True)
    documents = {"200-pages": fixture(200), "201-pages": fixture(201),
                 "49-mib-embedded": fixture(1, 49 * 1024 * 1024),
                 "51-mib-embedded": fixture(1, 51 * 1024 * 1024)}
    report["fixtures"] = {}
    for name, data in documents.items():
        path = directory / (name + ".pdf")
        path.write_bytes(data)
        report["fixtures"][name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    options = {"info": {}, "text": {}, "text/search": {"pattern": r"CAPACITY_PAGE_\d{4}"},
               "encrypt": {"userPassword": "capacity-reader", "ownerPassword": "capacity-owner"},
               "watermark": {"type": "TEXT", "text": "CAPACITY_WATERMARK", "pages": "1,200", "angle": 0}}
    records = report["records"]
    for operation, fields in options.items():
        result, data = post(origin, operation, documents["200-pages"], fields)
        result["phase"] = "200-page-sequential"; records.append(result)
        require(result, 200); validate(operation, data, 200)
    result, data = post(origin, "info", documents["49-mib-embedded"], {})
    result["phase"] = "near-upload-limit"; records.append(result)
    require(result, 200); validate("info", data, 1)
    for name, code in [("201-pages", "PAGE_LIMIT"), ("51-mib-embedded", "FILE_TOO_LARGE")]:
        result, _ = post(origin, "info", documents[name], {})
        result["phase"] = name; records.append(result); require(result, 413, code)
    mixed = ["info", "text/search", "encrypt", "watermark"]
    for round_number in range(2):
        barrier = threading.Barrier(len(mixed))
        with ThreadPoolExecutor(max_workers=len(mixed)) as pool:
            futures = [(operation, pool.submit(post, origin, operation, documents["200-pages"], options[operation], barrier)) for operation in mixed]
            accepted = 0
            for operation, future in futures:
                result, data = future.result()
                result.update(phase="mixed-burst", round=round_number + 1); records.append(result)
                if result["status"] == 200:
                    accepted += 1; validate(operation, data, 200)
                else:
                    require(result, 429, "CAPACITY_EXCEEDED")
            expect(accepted > 0, "No mixed request succeeded")
    pair = ["encrypt", "watermark"]
    barrier = threading.Barrier(2)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [(operation, pool.submit(post, origin, operation, documents["200-pages"], options[operation], barrier)) for operation in pair]
        for operation, future in futures:
            result, data = future.result()
            result["phase"] = "mixed-pair"; records.append(result)
            require(result, 200); validate(operation, data, 200)
    result, data = post(origin, "info", documents["200-pages"], {})
    result["phase"] = "recovery"; records.append(result); require(result, 200); validate("info", data, 200)
    report["status"] = "PASS"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    report = {"format": "pdf-workbench-large-mixed-v1", "status": "RUNNING",
              "measured_utc": datetime.now(timezone.utc).isoformat(), "records": [],
              "scope": "Synthetic 200-page text, embedded byte-size fixtures and two four-way mixed bursts plus an encryption/watermark pair; not a soak or public throughput test"}
    try:
        verify(args.base_url, args.output_directory, report)
    except Exception as error:
        report.update(status="FAIL", error_type=type(error).__name__, error=str(error))
        raise
    finally:
        args.output_directory.mkdir(parents=True, exist_ok=True)
        (args.output_directory / "large-mixed-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": report["status"], "completed_records": len(report["records"])}))


if __name__ == "__main__":
    main()
