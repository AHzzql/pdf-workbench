"""核对 Part A 的需求、图形、接口和 Word 格式。"""
from pathlib import Path
from zipfile import ZipFile
from collections import Counter
import xml.etree.ElementTree as E
import json,re,hashlib
from PIL import Image
from content import REQUIREMENTS,INTERFACES

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'docs/part-a';QA=ROOT/'backend/target/part-a-qa'
NS={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}

def run():
    checks=[]
    def check(name,condition,detail=''):
        checks.append({'check':name,'passed':bool(condition),'detail':detail})
    spec=json.loads((OUT/'openapi.json').read_text(encoding='utf-8'))
    check('OpenAPI version',spec['openapi']=='3.0.3')
    check('Eleven core operations',len(spec['paths'])==11 and all(set(v)=={'post'} for v in spec['paths'].values()))
    all_requirements={r['id'] for r in REQUIREMENTS if r['scope']=='纳入'}
    case_ids=[r['case'] for r in REQUIREMENTS if r['scope']=='纳入']
    check('Twenty-two unique acceptance conditions',len(case_ids)==22 and len(set(case_ids))==22)
    schemas=spec['components']['schemas'];opids=[];reference_count=0
    def walk(v):
        nonlocal reference_count
        if isinstance(v,dict):
            if '$ref' in v:
                reference_count+=1;p=v['$ref'];target=spec
                for key in p[2:].split('/'):target=target[key]
                assert p.startswith('#/'),p
            for item in v.values():walk(item)
        elif isinstance(v,list):
            for item in v:walk(item)
    try:walk(spec);check('Schema references resolve',True,str(reference_count))
    except Exception as e:check('Schema references resolve',False,str(e))
    for path,entry in spec['paths'].items():
        op=entry['post'];opids.append(op['operationId']);body=op['requestBody']['content']['multipart/form-data']['schema']
        check('Required PDF '+path,'file' in body['required'] and body['properties']['file']=={'type':'string','format':'binary','description':'PDF，最大 50 MiB、200 页。'})
        check('Required property names '+path,set(body['required'])<=set(body['properties']))
        check('Success and error responses '+path,'200' in op['responses'] and all(s in op['responses'] for s in ['400','403','413','415','422','429','500','503','504']))
        check('Requirement references '+path,set(op['x-requirements'])<=all_requirements)
        for k,v in body['properties'].items():
            if 'password' in k.lower():check('Write-only credential '+path+' '+k,v.get('writeOnly') is True and 'example' not in v and 'default' not in v)
        check('No query parameters '+path,'parameters' not in op)
    check('Operation IDs unique',len(opids)==len(set(opids)))
    check('Block properties',{'page','text','bounds','font','fontSize','fillColor'}<=set(schemas['TextBlock']['properties']))
    check('Page count',schemas['DocumentInfo']['properties']['pageCount'].get('minimum')==0)
    check('Three integrity states',schemas['SignatureResult']['properties']['integrity']['enum']==['VALID','INVALID','UNKNOWN'])
    check('Unknown revision state',schemas['SignatureResult']['properties']['hasLaterRevision'].get('nullable') is True)
    contract=(OUT/'swagger/contract.js').read_text(encoding='utf-8').removeprefix('window.PDF_CORE_OPENAPI = ').rstrip().removesuffix(';')
    check('Swagger uses exact contract',json.loads(contract)==spec)
    html=(OUT/'swagger/index.html').read_text(encoding='utf-8')
    check('Offline Swagger assets',all((OUT/'swagger'/p).exists() for p in ['vendor/swagger-ui.css','vendor/swagger-ui-bundle.js','vendor/LICENSE','contract.js']))
    check('No remote validator','validatorUrl:null' in html and 'https://' not in html)
    graphs={}
    for p in (OUT/'diagrams').glob('*.json'):
        if p.name=='manifest.json':continue
        g=json.loads(p.read_text(encoding='utf-8'));graphs[g['id']]=g
        ids={n['id'] for n in g['nodes']}
        check('Graph endpoints '+g['id'],all(e['from'] in ids and e['to'] in ids for e in g['edges']))
        check('Unique nodes '+g['id'],len(ids)==len(g['nodes']))
        E.parse(OUT/'diagrams'/(g['id']+'.svg'))
        with Image.open(OUT/'diagrams'/(g['id']+'.png')) as im:
            check('Diagram image dimensions '+g['id'],im.width>=1000 and im.height>=500)
    dfd_ids=set().union(*({n['id'] for n in g['nodes']} for k,g in graphs.items() if k not in ['07-wnd','08-architecture'] and not k.startswith('09-')))
    wnd_ids={n['id'] for n in graphs['07-wnd']['nodes']}
    api_ids={r[0] for r in INTERFACES}
    for r in REQUIREMENTS:
        if r['scope']!='纳入':continue
        check('DFD trace '+r['id'],set(r['dfd'].split('/'))<=dfd_ids)
        check('WND trace '+r['id'],set(r['wnd'].split('/'))<=wnd_ids)
        expected=api_ids if '—' in r['api'] else set(r['api'].split('/'))
        check('API trace '+r['id'],expected<=api_ids)
        actual={op['post']['summary'].split()[0] for op in spec['paths'].values() if r['id'] in op['post']['x-requirements']}
        check('Contract coverage '+r['id'],actual==expected,str(sorted(actual)))
    check('Ten figures',len(graphs)==10)
    with ZipFile(OUT/'PartA需求规格说明书.docx') as z:
        doc=E.fromstring(z.read('word/document.xml'));styles=E.fromstring(z.read('word/styles.xml'))
        text='\n'.join(''.join(t.text or '' for t in p.findall('.//w:t',NS)) for p in doc.findall('.//w:p',NS))
        check('All requirements in Word',all(r['id'] in text and r['title'] in text for r in REQUIREMENTS))
        check('All acceptance IDs in Word',all(c in text for c in case_ids))
        check('No defensive or conversational text',not any(x in text for x in ['按你的要求','待用户确认','不代表已经实现','不生成虚构','不能冒充','老师未提出','作为 AI','为你生成','未经验证不得','不增加老师']))
        check('No extension chapters',not any(x in text for x in ['智能阅读','agent','Agent','账户注册','登录模块','AI 辅助']))
        colored=[]
        for tree in [doc,styles]:
            for c in tree.findall('.//w:color',NS):
                value=c.get('{'+NS['w']+'}val');theme=c.get('{'+NS['w']+'}themeColor')
                if value not in [None,'000000','auto'] or theme:colored.append((value,theme))
        check('Black document text',not colored,str(colored[:8]))
        shading=[e.get('{'+NS['w']+'}fill') for e in doc.findall('.//w:tbl//w:shd',NS)]
        check('No colored table fills',all(v in [None,'auto','FFFFFF','ffffff'] for v in shading))
        check('Automatic TOC field',any('TOC ' in (x.text or '') for x in doc.findall('.//w:instrText',NS)))
        check('No tracked changes',not doc.findall('.//w:ins',NS) and not doc.findall('.//w:del',NS))
        check('Ten embedded figures',len(doc.findall('.//w:drawing',NS))==10)
    baseline=json.loads((Path(__file__).with_name('baseline-documents.json')).read_text(encoding='utf-8'))
    check('Original Word files unchanged',all(hashlib.sha256((ROOT/k).read_bytes()).hexdigest()==v for k,v in baseline.items()))
    result={'checks':len(checks),'passed':sum(c['passed'] for c in checks),'failures':[c for c in checks if not c['passed']],'details':checks}
    QA.mkdir(parents=True,exist_ok=True);(QA/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='details'},ensure_ascii=False))
    if result['failures']:raise SystemExit(1)

if __name__=='__main__':run()
