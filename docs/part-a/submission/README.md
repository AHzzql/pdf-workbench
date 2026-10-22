# PDF Extractor Part A

`PartA_Submission.docx` follows the supplied Part A submission template. Its body, tables, figure labels, captions and interface descriptions are in English. The original cover and member table are retained. The Chinese reference specification remains in the parent directory.

The submission contains two requirement grids, the five prescribed DFD groups, a supplemental watermark DFD, the common error package, window navigation, an interface wireframe and a captured English Swagger prototype. The OpenAPI contract retains the eleven core operations and their existing input/output constraints. Navigation identifiers link each operation to the English WND.

## Local access

- Document and figures: `/part-a/submission/`
- Swagger prototype: `/part-a/submission/swagger/`
- OpenAPI contract: `/part-a/submission/openapi.json`

Swagger uses the local vendor assets in `../swagger/vendor`; no remote validator or authorisation persistence is enabled.

## Build and verification

From the repository root, use the document Python environment with `python-docx`, Pillow and `pypdfium2`:

```powershell
python tools/part_a/submission.py
.\tools\part_a\render_submission.ps1
python tools/part_a/review_submission.py
python tools/part_a/check_submission.py
.\deploy\Test-Local.ps1
```

The native Windows Word automation updates the template's table of contents and page fields before exporting the review PDF. It closes only the document it opened. Page images, review sheets and check results are written to `backend/target/part-a-submission-qa`, outside the submission package. The screenshot in `diagrams/swagger-prototype.png` is a browser capture of the English Swagger page; the other diagrams are reproducible SVG/PNG figures.
