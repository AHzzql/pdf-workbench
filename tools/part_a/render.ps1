[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$input=Join-Path $root 'docs/part-a/PartA需求规格说明书.docx'
$out=Join-Path $root 'backend/target/part-a-qa'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$word=$null; $doc=$null; $owned=$false; $alerts=$null; $security=$null
try {
    $word=New-Object -ComObject Word.Application
    $owned=$word.Documents.Count -eq 0
    $alerts=$word.DisplayAlerts; $security=$word.AutomationSecurity
    if ($owned) { $word.Visible=$false }
    $word.DisplayAlerts=0
    $word.AutomationSecurity=3
    $doc=$word.Documents.Open($input, $false, $false)
    $doc.Fields.Update() | Out-Null
    foreach ($toc in $doc.TablesOfContents) { $toc.Update() }
    foreach ($toc in $doc.TablesOfContents) {
        $toc.Range.Font.Size=12
        $toc.Range.Font.Color=0
        $toc.Range.ParagraphFormat.SpaceBefore=0
        $toc.Range.ParagraphFormat.SpaceAfter=0
        $toc.Range.ParagraphFormat.LineSpacingRule=4
        $toc.Range.ParagraphFormat.LineSpacing=20
    }
    foreach ($id in @(-86,-87)) {
        $doc.Styles.Item($id).Font.Color=0
        $doc.Styles.Item($id).Font.Underline=0
    }
    $doc.Repaginate()
    foreach ($toc in $doc.TablesOfContents) { $toc.UpdatePageNumbers() }
    $doc.Save()
    $pdf=Join-Path $out 'PartA.pdf'
    $doc.ExportAsFixedFormat($pdf,17)
    [pscustomobject]@{Pages=$doc.ComputeStatistics(2);Pdf=$pdf;Document=$input} | ConvertTo-Json -Compress
} finally {
    if ($doc) { $doc.Close(0); [Runtime.InteropServices.Marshal]::ReleaseComObject($doc) | Out-Null }
    if ($word) {
        if ($null -ne $alerts) { $word.DisplayAlerts=$alerts }
        if ($null -ne $security) { $word.AutomationSecurity=$security }
        if ($owned -and $word.Documents.Count -eq 0) { $word.Quit() }
        [Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
    }
    [GC]::Collect();[GC]::WaitForPendingFinalizers()
}
