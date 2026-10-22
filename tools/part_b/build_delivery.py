"""Build and verify the Windows Part B delivery using version-controlled files."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "backend/target/part-b-delivery"
PREFIX = "PDF_Extractor_PartB"
PREFIXES = ("backend/src/", "frontend/", "deploy/", "docs/part-a/submission/",
            "docs/part-a/swagger/vendor/", "docs/part-b/diagrams/",
            "docs/part-b/evidence/", "docs/part-b/presentation/", "docs/part-b/delivery/")
FILES = {"workbench.cmd", "backend/pom.xml", "docs/part-b/PartB_Submission.docx",
         "docs/part-b/DELIVERY_README.md"}
FORBIDDEN = {".git", ".codex", ".agents", "node_modules", "runtime", "__pycache__",
             "test-results", "playwright-report", "coverage"}
SUFFIXES = {".p12", ".pfx", ".key", ".pem", ".log"}
PRIVATE_KEY = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]+")

def sha(data):
    return hashlib.sha256(data).hexdigest()

def safe_name(name):
    p = PurePosixPath(name)
    return bool(name) and not p.is_absolute() and "\\" not in name and ":" not in name and all(
        part not in {"", ".", ".."} for part in name.split("/"))

def permitted(name):
    p = PurePosixPath(name)
    if not safe_name(name) or any(part in FORBIDDEN or part.startswith(".env") or part.startswith("~$") for part in p.parts):
        return False
    if p.suffix.lower() in SUFFIXES or "/config/application-local." in name:
        return False
    return True

def selected(name):
    return permitted(name) and not name.startswith("frontend/dist/") and (name in FILES or name.startswith(PREFIXES))

def git(*args):
    executable = os.environ.get("DELIVERY_GIT") or shutil.which("git")
    if not executable:
        raise RuntimeError("Git is required to select version-controlled source files")
    return subprocess.check_output([executable, "-c", f"safe.directory={ROOT.as_posix()}", *args], cwd=ROOT)

def local_secrets(root):
    values = []
    for rel in ("deploy/.env.local", "backend/config/application-local.yml"):
        p = root / rel
        if p.is_file():
            for line in p.read_text(encoding="utf-8-sig").splitlines():
                match = re.match(r"\s*[^#:=]*(?:PASSWORD|SECRET|KEY|TOKEN|CREDENTIAL)[^:=]*[:=]\s*(.*?)\s*$", line, re.I)
                if match:
                    value = match[1].strip("'\"")
                    if len(value) >= 12 and not value.startswith("$" + "{"):
                        values.append(value.encode())
    return values

def check_payload(name, data, secrets):
    if PRIVATE_KEY.search(data) or any(value in data for value in secrets):
        raise ValueError(f"Private runtime material found in {name}")

def application_jar(root, secrets):
    path = root / "backend/target/pdf-workbench-1.1.0.jar"
    data = path.read_bytes()
    check_payload(path.name, data, secrets)
    count = 0
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.startswith("BOOT-INF/classes/") and not n.endswith("/"):
                relative = n.removeprefix("BOOT-INF/classes/")
                if "application-local" in relative:
                    raise ValueError("Local configuration embedded in executable")
                payload = z.read(n)
                check_payload(n, payload, secrets)
                compiled = root / "backend/target/classes" / relative
                if not compiled.is_file() or payload != compiled.read_bytes():
                    raise ValueError(f"Executable differs from compiled classes: {relative}")
                count += 1
        if not count or "BOOT-INF/classes/cn/edu/pdfworkbench/PdfWorkbenchApplication.class" not in z.namelist():
            raise ValueError("Not the expected Spring Boot executable")
    return data, count

def verify(path):
    with zipfile.ZipFile(path) as z:
        if z.testzip():
            raise ValueError("Corrupt delivery ZIP")
        names = z.namelist()
        if len(names) != len(set(names)) or any(not safe_name(n) or not n.startswith(PREFIX + "/") or not permitted(n.removeprefix(PREFIX + "/")) for n in names):
            raise ValueError("Unsafe or duplicate ZIP paths")
        manifest = json.loads(z.read(PREFIX + "/MANIFEST.json"))
        expected = {PREFIX + "/" + f["path"] for f in manifest["files"]} | {PREFIX + "/MANIFEST.json"}
        if set(names) != expected:
            raise ValueError("Manifest and ZIP entries differ")
        for f in manifest["files"]:
            data = z.read(PREFIX + "/" + f["path"])
            if len(data) != f["bytes"] or sha(data) != f["sha256"]:
                raise ValueError("SHA-256 mismatch: " + f["path"])
        required = {"README.md", "backend/pom.xml", "backend/target/pdf-workbench-1.1.0.jar",
                    "frontend/dist/index.html", "deploy/compose.yml", "deploy/Start-Web.ps1",
                    "deploy/Start-Worker.ps1", "docs/part-a/submission/PartA_Submission.docx",
                    "docs/part-b/PartB_Submission.docx", "docs/part-b/presentation/PartB_Demonstration.pptx"}
        if not required <= {f["path"] for f in manifest["files"]}:
            raise ValueError("Missing delivery artifacts")
    return {"files": len(manifest["files"]), "bytes": path.stat().st_size, "sha256": sha(path.read_bytes())}

def build():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    secrets = local_secrets(ROOT)
    tracked = git("ls-files", "-z").decode("utf-8").split("\0")
    payloads = {}
    for name in sorted(n for n in tracked if n and selected(n)):
        p = ROOT / name
        if not p.is_file() or p.is_symlink():
            raise ValueError("Missing or linked source: " + name)
        data = p.read_bytes()
        check_payload(name, data, secrets)
        payloads[name] = data
    jar, count = application_jar(ROOT, secrets)
    payloads["backend/target/pdf-workbench-1.1.0.jar"] = jar
    for p in sorted((ROOT / "frontend/dist").rglob("*")):
        if p.is_file():
            if p.is_symlink():
                raise ValueError("Linked frontend asset")
            name = p.relative_to(ROOT).as_posix()
            data = p.read_bytes()
            check_payload(name, data, secrets)
            payloads[name] = data
    # Publish only the English Part A submission in the delivery.
    payloads["docs/part-a/index.html"] = b'<!doctype html><html lang="en"><meta charset="utf-8"><title>PDF Extractor Part A</title><h1>PDF Extractor Part A</h1><a href="submission/">English requirements and design submission</a></html>'
    payloads["README.md"] = (ROOT / "docs/part-b/DELIVERY_README.md").read_bytes()
    manifest = {"format": "pdf-extractor-part-b-delivery-v1", "created_utc": datetime.now(timezone.utc).isoformat(),
                "source_base_commit": git("rev-parse", "HEAD").decode().strip(),
                "source_worktree_changes": sorted(n for n in git("diff", "HEAD", "--name-only", "-z").decode("utf-8").split("\0") if n and selected(n)),
                "compiled_application_files_checked": count,
                "files": [{"path": n, "bytes": len(d), "sha256": sha(d)} for n, d in sorted(payloads.items())]}
    payloads["MANIFEST.json"] = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    candidate = OUTPUT / "delivery.tmp.zip"
    try:
        with zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            for name, data in sorted(payloads.items()):
                z.writestr(PREFIX + "/" + name, data)
        result = verify(candidate)
        final = OUTPUT / "PDF_Extractor_PartB_Delivery.zip"
        candidate.replace(final)
        result["path"] = str(final)
        (OUTPUT / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        (OUTPUT / (final.name + ".sha256")).write_text(result["sha256"] + "  " + final.name + "\n", encoding="ascii")
        print(json.dumps(result, ensure_ascii=False))
    finally:
        candidate.unlink(missing_ok=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    if args.verify:
        print(json.dumps(verify(args.verify), ensure_ascii=False))
    else:
        build()
