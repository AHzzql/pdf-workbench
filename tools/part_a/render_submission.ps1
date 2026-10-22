#requires -Version 7.2
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$input=(Resolve-Path -LiteralPath (Join-Path $root 'docs/part-a/submission/PartA_Submission.docx')).ProviderPath
$out=Join-Path $root 'backend/target/part-a-submission-qa'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$word=$null; $doc=$null; $owned=$false; $alerts=$null; $security=$null
try {
    $word=New-Object -ComObject Word.Application
    $owned=$word.Documents.Count -eq 0
    $alerts=$word.DisplayAlerts; $security=$word.AutomationSecurity
    if($owned){$word.Visible=$false}
    $word.DisplayAlerts=0; $word.AutomationSecurity=3
    $doc=$word.Documents.Open($input,$false,$false)
    if($doc.ReadOnly){throw 'Submission document is read-only or in use.'}
    $doc.Fields.Update() | Out-Null
    foreach($toc in $doc.TablesOfContents){
        $toc.Update()
        $toc.Range.Font.Name='Arial'; $toc.Range.Font.Size=11; $toc.Range.Font.Color=0
        $toc.Range.ParagraphFormat.SpaceBefore=0; $toc.Range.ParagraphFormat.SpaceAfter=0
        $toc.Range.ParagraphFormat.LineSpacingRule=4; $toc.Range.ParagraphFormat.LineSpacing=16
    }
    $doc.Repaginate()
    foreach($toc in $doc.TablesOfContents){$toc.UpdatePageNumbers()}
    $doc.Save()
    $pdf=Join-Path $out 'PartA.pdf'
    $doc.ExportAsFixedFormat($pdf,17)
    $pages=$doc.ComputeStatistics(2)
    $doc.Close(0);[Runtime.InteropServices.Marshal]::ReleaseComObject($doc)|Out-Null;$doc=$null
    [pscustomobject]@{WordSHA256=(Get-FileHash -LiteralPath $input -Algorithm SHA256).Hash;PdfSHA256=(Get-FileHash -LiteralPath $pdf -Algorithm SHA256).Hash;Pages=$pages} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'render.json') -Encoding utf8
    [pscustomobject]@{Pages=$pages;PDF=$pdf;Document=$input}|ConvertTo-Json -Compress
} finally {
    if($doc){$doc.Close(0);[Runtime.InteropServices.Marshal]::ReleaseComObject($doc)|Out-Null}
    if($word){
        if($null -ne $alerts){$word.DisplayAlerts=$alerts}
        if($null -ne $security){$word.AutomationSecurity=$security}
        if($owned -and $word.Documents.Count -eq 0){$word.Quit()}
        [Runtime.InteropServices.Marshal]::ReleaseComObject($word)|Out-Null
    }
    [GC]::Collect();[GC]::WaitForPendingFinalizers()
}
