"""Collect reproducible core PDF test evidence without machine properties."""
from pathlib import Path
import hashlib,json,shutil,xml.etree.ElementTree as ET
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'docs/part-b/evidence'
CLASSES=['PdfApiTest','PdfTextUtilsTest','PdfTableUtilsTest','PdfImageUtilsTest','PdfInfoUtilsTest','PdfEncryptionUtilsTest','PdfSignatureUtilsTest','PdfWatermarkUtilsTest','PageSelectionTest','PdfServiceLimitsTest','PdfOutputFailureTest']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def collect():
    OUT.mkdir(parents=True,exist_ok=True)
    combined=ET.Element('testsuites');sources=[];count=0
    for name in CLASSES:
        source=ROOT/f'backend/target/surefire-reports/TEST-cn.edu.pdfworkbench.{name}.xml'
        raw=ET.parse(source).getroot()
        assert all(int(raw.get(k,'0'))==0 for k in ['failures','errors','skipped']),name
        suite=ET.SubElement(combined,'testsuite',{k:v for k,v in raw.attrib.items() if k in ['name','tests','errors','failures','skipped','time','timestamp']})
        cases=raw.findall('testcase');assert len(cases)==int(raw.get('tests')),name
        for case in cases:
            assert not any(case.find(k) is not None for k in ['error','failure','skipped'])
            ET.SubElement(suite,'testcase',{k:v for k,v in case.attrib.items() if k in ['name','classname','time']})
        count+=len(cases);sources.append({'suite':name,'source_report_sha256':sha(source),'timestamp':raw.get('timestamp'),'tests':len(cases)})
    assert count==93,count
    ET.indent(combined);ET.ElementTree(combined).write(OUT/'core-junit.xml',encoding='utf-8',xml_declaration=True)
    log=(ROOT/'backend/target/part-b-core-tests.log').read_text('utf-8-sig')
    assert 'Tests run: 93, Failures: 0, Errors: 0, Skipped: 0' in log and 'BUILD SUCCESS' in log
    lines=[x for x in log.splitlines() if x.startswith('[INFO]') and any(v in x for v in ['Tests run:','BUILD SUCCESS','Total time:','Finished at:'])]
    (OUT/'junit-summary.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    browser=(ROOT/'backend/target/part-b-browser-tests.log').read_text('utf-8-sig')
    assert '8 passed' in browser
    (OUT/'browser-summary.txt').write_text(browser,encoding='utf-8')
    for name in ['api-sample.pdf','signed.pdf','removed.pdf','no-images.pdf']:
        shutil.copyfile(ROOT/'backend/target/verification'/name,OUT/name)
    for name in ['02-text.png','03-table.png','04-signature.png','core-partial-images.png','core-unknown-revision.png']:
        shutil.copyfile(ROOT/'frontend/test-results'/name,OUT/name)
    evidence=[{'path':p.relative_to(OUT).as_posix(),'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(OUT.iterdir()) if p.is_file() and p.name!='manifest.json']
    code=[]
    for folder in ['backend/src/main/java','backend/src/main/resources','backend/src/test/java','frontend/src']:
        for p in sorted((ROOT/folder).rglob('*')):
            if p.is_file() and p.suffix in ['.java','.yml','.sql','.vue','.ts','.css']:
                code.append({'path':p.relative_to(ROOT).as_posix(),'sha256':sha(p)})
    for path in ['backend/pom.xml','frontend/package.json','frontend/package-lock.json','frontend/tests/e2e/workbench.spec.ts']:
        code.append({'path':path,'sha256':sha(ROOT/path)})
    properties=ET.parse(ROOT/f'backend/target/surefire-reports/TEST-cn.edu.pdfworkbench.{CLASSES[0]}.xml').getroot().find('properties')
    environment={p.get('name'):p.get('value') for p in properties if p.get('name') in ['java.version','java.vendor','os.name','os.arch']}
    result={'collected_at':datetime.now(timezone.utc).isoformat(),'junit_command':'mvn.cmd -B -o -Dtest='+','.join(CLASSES)+' test','junit_tests':count,'junit_failures':0,'junit_errors':0,'junit_skipped':0,'browser_tests':8,'environment':environment,'suite_sources':sources,'files':evidence,'source_files':code}
    (OUT/'manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'junit':count,'browser':8,'evidence_files':len(evidence)}))
if __name__=='__main__':collect()
