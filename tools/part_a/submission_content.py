"""English content for the supplied Part A submission structure."""
PROJECT_INTRO = [
    "PDF documents combine fixed page layouts with text, embedded images, metadata and security information. A reader may need a passage from a particular page, a reusable table, an original image, or a protected copy of a document. These tasks become difficult when separate applications use different page conventions, obscure extraction limitations, or overwrite the source. The PDF Extractor project brings the operations into one browser-based workbench while preserving the relationship between a result and its source page.",
    "The intended users include document readers, administrative staff and people responsible for protecting or checking documents. A user selects one PDF, inspects its pages, chooses an operation and supplies the required parameters. The application displays structured results and provides downloads where appropriate. Its scope includes page text and text attributes, regular-expression search, bookmarks, metadata, annotations, simple grid tables, embedded images, encryption, digital signatures and watermarks. Attachment extraction is an optional requirement outside the selected scope. Optical character recognition and unrestricted PDF editing are also outside the scope.",
    "The workbench is a web application served from a Windows-hosted Java environment through a same-origin gateway. Each operation processes an uploaded copy and leaves the original unchanged. Passwords and signing material are supplied only for the operation that needs them. File size, page count and output limits provide explicit operating boundaries. This specification records user needs, resolved ambiguities, assumptions, functional data flows and window navigation so that implementation and acceptance testing can use the same definitions."
]
FUNCTIONAL_INTRO = [
    "The functional requirements describe how a selected PDF and operation parameters become information or a new document. The common input boundary accepts one PDF, verifies its content type, checks the applicable limits and opens it with the supplied password. A valid input can then follow an extraction, information, protection, signature or watermarking flow. The resulting information retains relevant page references; generated files use distinct names and never replace the uploaded source.",
    "The data-flow diagrams use external entities for document users, rounded processes for transformations, and named arrows for data passed between them. The context diagram establishes the application boundary. The grouped diagrams then separate text-related requirements FR1 to FR9, table requirements FR10 and FR11, image requirement FR12, encryption requirements FR13 to FR16, and signature requirements FR17 to FR19. The watermark flow supplements these groups because watermarking is required by the project brief. FR8 remains excluded, while the optional annotation requirement FR9 is included.",
    "Error handling is shared across the flows rather than being redefined for each tool. Missing input, invalid page ranges, password failures and unsupported content lead to specific feedback without delivering a misleading successful file. Empty extraction results are distinguished from invalid documents, and signature integrity is distinguished from certificate trust. The window navigation diagram connects file selection, preview, tool panels, result inspection and saving. The accompanying Swagger prototype exposes the same operation parameters and response models, allowing the interface design and the acceptance conditions to be checked against a common contract."
]
FEATURES = [
    "Select one PDF, display its page count and preview its pages (FR1 and PAGE01).",
    "Extract page text, positioned text blocks and paragraph order; search with RE2/J regular expressions (FR2–FR5).",
    "Read bookmarks, standard and custom metadata, and highlight or note annotations (FR6, FR7 and FR9).",
    "Detect simple ruled tables, preserve two-dimensional cross-table cells and export CSV (FR10–FR11).",
    "Export unique embedded image resources, original encoded streams and available PNG/JPG conversions (FR12).",
    "Create a new encrypted PDF with user and owner passwords, permissions and supported AES strength (FR13–FR16).",
    "Sign with a PKCS#12 certificate, report separate verification conclusions and remove signatures from an eligible copy after confirmation (FR17–FR19).",
    "Apply text or image watermarks to all or selected pages and download a separate output (WM01–WM02)."
]
STAKEHOLDERS = [
    ("End users", "Readers preparing quotations or extracting records", "Select pages, inspect results and save reusable output."),
    ("PDF/document administrators", "Staff managing controlled document copies", "Apply passwords, permissions, signatures and watermarks without changing the source."),
    ("Software developers", "Maintainers of the Java utilities and web workbench", "Use stable parameters, result models and reproducible acceptance conditions."),
    ("System administrators", "Operators of the Windows-hosted service", "Control file limits, temporary storage, access and deployment configuration."),
    ("Organisations managing sensitive documents", "An office handling signed contracts or restricted reports", "Limit exposure of passwords, private keys and document content."),
    ("Project assessor/client", "The recipient accepting the PDF Extractor", "Trace the required features to diagrams, interface operations and measurable results.")
]
NEEDS = [
    "Document readers and administrators use the application; specialist PDF programming knowledge is not required.",
    "Inputs are text-bearing, image-bearing, encrypted or signed PDFs whose supported features are defined in the scope matrix. Scanned pages do not imply OCR support.",
    "An input PDF is limited to 50 MiB and 200 pages. Image, certificate and output limits are checked separately.",
    "Each operation accepts one PDF. The workbench does not provide a multi-file batch-processing workflow.",
    "The original PDF is never overwritten. Signing, encryption, signature removal and watermarking create new files.",
    "Extracted information is displayed in the browser or returned as a download. The browser's download settings or save dialog determine the user's destination.",
    "Output formats include structured JSON, CSV, image ZIP packages and processed PDF files, according to the selected operation.",
    "An encrypted input requires an appropriate opening password and the permissions needed for the requested operation. Failure produces feedback rather than bypassing protection.",
    "Errors identify the affected input or operation through a stable code and a readable explanation; credentials and internal file paths are not included.",
    "Users can inspect structured results on screen and save supported exports. Status and error feedback remain available beside the active tool."
]
# ID, ambiguity, resolution, classification, source
AMBIGUITIES = [
    ("A01", "Does PNG/JPG export replace the original encoding?", "Keep the original encoded stream as a .bin entry and describe its filters; include a converted image when decoding succeeds.", "Design decision", "FR12; image export contract"),
    ("A02", "What if an image cannot be converted?", "Keep the original stream and mark the manifest entry RAW_ONLY with a diagnostic. Do not label that item EXPORTED.", "Constraint", "FR12; ImageManifest"),
    ("A03", "Should image resolution be preserved?", "Do not deliberately resample during export. Report resource pixel dimensions; PDF placement size and source pixels are separate attributes.", "Design decision", "FR12; image resource model"),
    ("A04", "How are duplicate image resources handled?", "Export one copy of each image XObject and retain every drawing occurrence in the manifest. Distinct resources remain distinct even when visually similar.", "Design decision", "FR12; occurrence model"),
    ("A05", "How are extracted images named?", "Use generated resource IDs for .bin and converted files. The ZIP manifest links each file to its ID, source, filters and occurrences.", "Design decision", "FR12; image ZIP contract"),
    ("A06", "Does a cross table require pivot calculation?", "Preserve the extracted row/column grid, including empty cells. No aggregation or inferred pivot statistics is required.", "Scope decision", "FR10–FR11"),
    ("A07", "Are attachments and annotations both mandatory?", "Both are optional in the brief. FR9 annotations is included; FR8 embedded attachments is excluded from this version.", "Scope decision", "Project brief FR8/FR9"),
    ("A08", "Does a valid signature mean a trusted certificate?", "Report integrity, certificate validity, trust and later revisions independently. Trust is not evaluated without a supplied trust anchor.", "Design decision", "FR18; verification model"),
    ("A09", "Does an empty result mean that parsing failed?", "Empty text or table results are successful outcomes with an explicit empty state. No images produces NO_IMAGES_FOUND rather than an empty success ZIP.", "Interface decision", "FR2/FR10/FR12; error contract"),
    ("A10", "What does a selected watermark page mean?", "Use one-based document page numbers; an empty page expression selects all pages. Place the watermark in PDF default user space.", "Design decision", "WM01/WM02; page parameters")
]
SOFTWARE = [
    ("Q01", "Who is the primary user?", "A document reader or administrator selecting pages and exporting information through a browser.", "User need", "Stakeholders; 1.3.2"),
    ("Q02", "Which operating system is supported?", "Windows hosts the Java application; Docker Desktop hosts the local gateway and infrastructure. Browser access is the user interface.", "Deployment constraint", "Windows deployment baseline"),
    ("Q03", "Which PDF versions are supported?", "Compatibility samples cover PDF 1.4, 1.7 and 2.0. Acceptance is feature-based; unsupported encodings or interactive features remain explicit limitations.", "Compatibility scope", "PDF compatibility fixtures"),
    ("Q04", "What is the maximum PDF size?", "50 MiB per PDF, up to 200 pages. The actual transferred length is checked; output is limited to 200 MiB per operation.", "Capacity constraint", "Service limits; NFR2"),
    ("Q05", "How are password-protected PDFs handled?", "Accept an operation-specific opening password; require the permission needed for extraction or modification; report missing, incorrect or insufficient credentials separately.", "Security requirement", "FR1; FR13–FR16"),
    ("Q06", "How are scanned/image-only PDFs handled?", "They may be previewed or yield embedded images. Text extraction may be empty; OCR is outside the selected scope.", "Scope constraint", "FR2/FR12"),
    ("Q07", "What is successful table extraction?", "Detect a closed horizontal/vertical grid, keep row and column relationships and empty cells, and export the selected table as quoted CSV when requested.", "Acceptance definition", "FR10/FR11"),
    ("Q08", "How is multi-column text ordered?", "Support ordinary horizontal single columns and regular double columns; expose diagnostics for complex layouts or missing text mappings rather than promise semantic reconstruction.", "Layout constraint", "FR3/FR5"),
    ("Q09", "Which image formats are supported?", "PNG and JPG conversion are available where decoding permits. Original streams are retained in the ZIP. Image watermark input is PNG/JPG, at most 20 MiB and 40 million pixels.", "Format constraint", "FR12; WM02"),
    ("Q10", "Which certificate formats are supported?", "Signing uses a PKCS#12 container holding an eligible RSA private key and certificate chain. Optional verification trust input is X.509 PEM/DER; certificate uploads are limited to 5 MiB.", "Format constraint", "FR17/FR18"),
    ("Q11", "Where are certificates and private keys stored?", "Uploaded signing material is staged for the current request and removed with its work directory; passwords and private keys are not part of ordinary results or logs.", "Security requirement", "Signing workflow; NFR4"),
    ("Q12", "Who may remove signatures?", "Require explicit operator confirmation and an unencrypted copy with modification permission. Remove signatures only from the new output; leave the input unchanged.", "Operation constraint", "FR19"),
    ("Q13", "What if signature verification fails?", "Return an INVALID integrity conclusion for detected tampering and UNKNOWN for unsupported or indeterminate verification, with certificate and revision fields reported separately.", "Result requirement", "FR18; verification model"),
    ("Q14", "Which pages receive watermarks?", "All pages when no page expression is supplied, otherwise only the validated selection. Reject direct watermark modification of signed documents.", "Scope constraint", "WM01/WM02"),
    ("Q15", "May the original document be overwritten?", "No. Process an uploaded copy and return a separate result file. An output-write failure must leave the original bytes unchanged.", "Integrity requirement", "NFR1/NFR4"),
    ("Q16", "Which outputs are required?", "JSON for structured information, CSV for a selected table, a ZIP with manifest and images, and PDF for document modifications.", "Interface requirement", "API01–API11"),
    ("Q17", "How are errors communicated?", "Show a readable explanation and stable code. Differentiate input, permission, unsupported-feature, capacity and output-write errors; keep secrets and internal paths out of feedback.", "Usability requirement", "Common error package"),
    ("Q18", "What performance is acceptable?", "Initial acceptance envelope: one PDF up to 50 MiB/200 pages, two concurrent core operations and output up to 200 MiB. A reproducible 20-page text fixture has a response-time target of 5 seconds on the test machine. The processing deadline is 90 seconds; sustained throughput depends on the workload.", "Capacity baseline", "Service limits; NFR2"),
    ("Q19", "Which security risks are addressed?", "Protect source files, credentials and signing keys; validate uploads and ranges; bound request concurrency and output; use same-origin transport and private service storage. Public TLS and least-privilege deployment require deployment configuration.", "Security constraint", "NFR4; deployment boundary"),
    ("Q20", "Which features are mandatory or optional?", "Include FR1–FR7 and FR10–FR19, plus watermarking and page count. Include optional FR9; exclude optional FR8. Exclude OCR, unrestricted PDF editing, full PDF creation, online collaboration and general format conversion.", "Scope decision", "Project brief; selected scope")
]
# title, intro, three subprocesses, outputs, acceptance, extra flow explanation
GROUPS = [
    ("Text Extraction FR1 through to FR9 DFD", "text", "This flow accepts a PDF and page selection, then separates text extraction, pattern matching and document-object reading.",
     ["P2.1 Extract page text\nFR2 / FR3 / FR5", "P2.2 Match expressions\nFR4", "P2.3 Read PDF objects\nFR6 / FR7 / FR9"],
     "Return page text, text blocks with bounds and style, paragraph records, regular-expression matches, bookmarks, metadata and annotations. Text offsets use UTF-16 units with an inclusive start and exclusive end; page numbers start at one.",
     "A selected-page fixture returns only the requested pages; each match identifies its source page and range. Invalid expressions or pages produce parameter errors. Bookmarks, metadata and annotations retain available source properties. FR8 attachment extraction has no active flow.",
     "Page count and input validation are shared with FR1. Document information is read through API03 rather than a separate duplicate parser interface; this also supplies the page total used by navigation."),
    ("Table Extraction FR10 and FR11 DFD", "tables", "This flow identifies a simple ruled grid and preserves its cells for display or CSV export.",
     ["P3.1 Detect grid lines\nFR10", "P3.2 Preserve cells\nFR10 / FR11", "P3.3 Export selected grid\nFR11"],
     "Return page-referenced tables with bounds and two-dimensional cell rows. An optional selected index produces CSV with escaping for commas, quotes and line breaks. Cross tables preserve their source grid rather than generate pivot statistics.",
     "A ruled table fixture preserves row/column counts and empty cells. Quoted CSV cells round-trip correctly. A page with no recognised grid returns an empty table collection, while an invalid table index returns a parameter error.",
     "Display and export share the same extracted cell model so that changing the output format does not change cell order or infer missing values."),
    ("Image Extract FR12 DFD", "images", "This flow separates original encoded image resources from optional PNG/JPG conversion.",
     ["P4.1 Find image resources\nFR12", "P4.2 Convert where possible\nFR12", "P4.3 Build image manifest\nFR12"],
     "Return a ZIP containing a manifest, original .bin streams and successful conversions. A manifest entry identifies the resource, source/filter details, pixel dimensions, occurrence locations and EXPORTED or RAW_ONLY status. Response headers report total and converted resources.",
     "Repeated uses of one XObject produce one exported resource and multiple occurrences. An unconvertible item retains its original stream and diagnostic. No embedded images produces NO_IMAGES_FOUND, not an empty success archive.",
     "The manifest and raw-stream paths make conversion failures visible while retaining source data. Pixel dimensions are reported independently of the image's placement on a PDF page."),
    ("PDF Encryption FR13 through to FR16 DFD", "encryption", "This flow validates the password and permission combination before creating a separate encrypted copy.",
     ["P5.1 Validate passwords\nFR13 / FR14", "P5.2 Set permissions\nFR15", "P5.3 Encrypt new copy\nFR16"],
     "Return an encrypted PDF using the selected supported AES key length. An empty user password permits opening without a password; the owner password must be non-empty and different from the user password. Print, copy and modification permissions are independent settings.",
     "A generated copy opens with the specified user credential and reports the intended permission flags and encryption strength. Invalid password combinations or unsupported key lengths do not produce a successful download; source bytes remain unchanged.",
     "Opening credentials belong to the input side of the boundary, while new user/owner passwords belong to the output policy. Neither credential is copied into ordinary response metadata."),
    ("Digital Signature FR17 through to FR19 DFD", "signatures", "This flow uses certificate material to sign, independently assess, or explicitly remove signatures from an eligible copy.",
     ["P6.1 Sign with PKCS#12\nFR17", "P6.2 Verify conclusions\nFR18", "P6.3 Remove after consent\nFR19"],
     "Signing returns a new PDF. Verification returns separate integrity, certificate validity, trust and revision conclusions with available signer details. Confirmed removal strips signature fields and their appearances from an eligible unencrypted copy while retaining unrelated content.",
     "A valid signed fixture verifies its integrity; tampering yields INVALID; an unsupported or indeterminate signature yields UNKNOWN. Missing trust input is not reported as trusted. Wrong certificate passwords or unknown aliases do not yield a signed file.",
     "Optional trust certificates are input to the verification branch only. Integrity, certificate trust and later modifications remain separate result fields, avoiding an ambiguous single valid/invalid label."),
    ("Watermarking DFD", "watermark", "This supplemental flow applies a text or image watermark to a validated set of pages.",
     ["P7.1 Select pages and type\nWM01 / WM02", "P7.2 Validate mark settings\nWM01 / WM02", "P7.3 Write marked copy\nWM01 / WM02"],
     "Return a separate watermarked PDF. Text requires text content; image mode requires an image. Both modes use page selection, position, angle and opacity, with image dimensions defining its aspect-preserving placement boundary.",
     "A selected-page fixture changes only those pages. Invalid dimensions, opacity, unsupported images and out-of-range pages are rejected. A signed input is not directly modified; the original remains unchanged.",
     "The brief requires text and image watermarks although the template's five numbered groups stop at FR19. WM01 and WM02 retain separate identifiers and use the same input-validation and output-delivery boundaries.")
]
ERRORS = [
    ("File does not exist / no input", "400 FILE_REQUIRED", "Select a non-empty PDF and submit it again."),
    ("File is not a PDF", "415 UNSUPPORTED_FORMAT", "The selected file is not a supported PDF."),
    ("PDF is corrupted", "422 DOCUMENT_INVALID", "The PDF cannot be parsed; select a valid copy."),
    ("PDF requires a password", "403 PDF_PASSWORD_REQUIRED", "Enter the PDF opening password."),
    ("Incorrect password", "403 PDF_PASSWORD_INVALID", "The opening password is incorrect; enter it again."),
    ("Page number does not exist", "400 INVALID_PAGES", "Select pages within the displayed page total."),
    ("No text found", "200; empty text/diagnostic", "No extractable text was found; OCR is outside scope."),
    ("No tables found", "200; empty collection", "No supported ruled table was recognised on these pages."),
    ("No images found", "422 NO_IMAGES_FOUND", "No embedded images are available for export."),
    ("Invalid regular expression", "400 INVALID_REGEX", "Correct the expression using supported RE2/J syntax."),
    ("Unsupported PDF feature", "422 PDF_UNSUPPORTED", "This PDF feature cannot be processed by the selected operation."),
    ("Invalid certificate", "422 CERTIFICATE_INVALID", "Select a compatible certificate/key container; a wrong container password has its own password error."),
    ("Invalid digital signature", "200; integrity INVALID", "The signature integrity check detected invalid signed data. Other verification conclusions remain separate."),
    ("Encryption failure", "422 ENCRYPTION_FAILED", "The encrypted copy could not be produced; the input remains unchanged."),
    ("Output file cannot be created", "500 OUTPUT_CREATE_FAILED", "Output storage is unavailable; retry when storage is restored."),
    ("Processing deadline exceeded", "504 PDF_TIMEOUT", "This operation exceeded its time limit; reduce the selected work or try another PDF."),
    ("Processing subprocess failed", "503 PDF_PROCESS_FAILED", "The processing task did not complete; no result file is delivered.")
]
API_NAMES = ["Extract page text and attributes", "Search with a regular expression", "Read information, pages, bookmarks and annotations", "Extract ruled tables", "Export one table as CSV", "Export raw images and PNG/JPG", "Create an encrypted permission-controlled copy", "Sign with a certificate", "Verify digital signatures", "Remove all signatures", "Apply a text or image watermark"]
