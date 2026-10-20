"""核心 PDF 操作的 OpenAPI 设计契约和本地 Swagger 页面。"""
from pathlib import Path
import json
from content import INTERFACES, REQUIREMENTS


def ref(name): return {'$ref':'#/components/schemas/'+name}
def obj(props, required=()):
    result={'type':'object','properties':props}
    if required:result['required']=list(required)
    return result

def build(out):
    string={'type':'string'};integer={'type':'integer'};number={'type':'number'}
    binary={'type':'string','format':'binary'}
    def array(item):return {'type':'array','items':item}
    def optional(s):return {**s,'nullable':True}
    rect=obj({k:{'type':'number','format':'float'} for k in ['x','y','width','height']},['x','y','width','height'])
    schemas={'Rect':rect}
    schemas['Color']=obj({'colorSpace':string,'components':array(number),'rgb':optional({'type':'string','pattern':'^#[0-9A-Fa-f]{6}$'})},['colorSpace','components'])
    text_props={'text':string,'start':integer,'end':integer,'bounds':ref('Rect'),'font':string,'fontSize':number,'fillColor':ref('Color')}
    schemas['TextSpan']=obj({**text_props,'baselineY':number,'strokeColor':ref('Color'),'renderingMode':integer},['text','start','end','bounds','font','fontSize'])
    schemas['TextBlock']=obj({**text_props,'page':{'type':'integer','minimum':1}},['page','text','start','end','bounds','font','fontSize','fillColor'])
    schemas['Paragraph']=obj({'text':string,'start':integer,'end':integer},['text','start','end'])
    schemas['PageText']=obj({'page':integer,'cropBox':ref('Rect'),'rotation':integer,'userUnit':number,'text':string,'spans':array(ref('TextSpan')),'blocks':array(ref('TextBlock')),'paragraphs':array(ref('Paragraph')),'diagnostics':array(string)},['page','text','spans','blocks','paragraphs','diagnostics'])
    schemas['TextResult']=obj({'pages':array(ref('PageText'))},['pages'])
    schemas['SearchMatch']=obj({'page':integer,'text':string,'start':integer,'end':integer,'bounds':array(ref('Rect'))},['page','text','start','end','bounds'])
    schemas['SearchMatchList']=array(ref('SearchMatch'))
    schemas['Bookmark']=obj({'title':optional(string),'page':optional(integer),'externalUri':optional(string),'children':array(ref('Bookmark'))},['title','page','children'])
    schemas['Annotation']=obj({'page':integer,'type':string,'author':optional(string),'contents':optional(string),'bounds':{**rect,'nullable':True},'quadPoints':array(number)},['page','type','contents','bounds'])
    schemas['DocumentInfo']=obj({'pageCount':{'type':'integer','minimum':0},'standardMetadata':{'type':'object','additionalProperties':string},'customMetadata':{'type':'object','additionalProperties':string},'xmp':optional(string),'bookmarks':array(ref('Bookmark')),'annotations':array(ref('Annotation')),'diagnostics':array(string)},['pageCount','standardMetadata','customMetadata','bookmarks','annotations','diagnostics'])
    schemas['TableData']=obj({'page':integer,'bounds':ref('Rect'),'rows':array(array(string))},['page','bounds','rows'])
    schemas['TableList']=array(ref('TableData'))
    schemas['CertificateSummary']=obj({'subject':string,'issuer':string,'serialNumber':string,'notBefore':{'type':'string','format':'date-time'},'notAfter':{'type':'string','format':'date-time'}},['subject','issuer','serialNumber','notBefore','notAfter'])
    schemas['SignatureResult']=obj({'name':optional(string),'signer':optional(string),'algorithm':optional(string),'integrity':{'type':'string','enum':['VALID','INVALID','UNKNOWN'],'description':'VALID 有效；INVALID 无效；UNKNOWN 无法验证。'},'hasLaterRevision':{'type':'boolean','nullable':True,'description':'无法判定时为 null。'},'certificateValidity':{'type':'string','enum':['VALID','EXPIRED','NOT_YET_VALID','UNKNOWN']},'trustStatus':{'type':'string','enum':['TRUSTED','UNTRUSTED','NOT_EVALUATED']},'revocationChecked':{'type':'boolean'},'certificate':{**schemas['CertificateSummary'],'nullable':True},'reason':optional(string)},['integrity','hasLaterRevision','certificateValidity','trustStatus','revocationChecked','reason'])
    schemas['SignatureList']=array(ref('SignatureResult'))
    schemas['ImageOccurrence']=obj({'page':integer,'bounds':ref('Rect')},['page','bounds'])
    schemas['ImageManifestItem']=obj({'id':string,'source':string,'width':integer,'height':integer,'filters':string,'occurrences':array(ref('ImageOccurrence')),'rawFile':string,'file':optional(string),'status':{'type':'string','enum':['EXPORTED','RAW_ONLY']},'diagnostic':optional(string)},['id','width','height','occurrences','rawFile','status'])
    schemas['ImageManifest']=array(ref('ImageManifestItem'))
    schemas['Error']=obj({'status':integer,'code':string,'message':string},['status','code','message'])
    common={'file':{**binary,'description':'PDF，最大 50 MiB、200 页。'},'password':{'type':'string','format':'password','writeOnly':True,'description':'本次操作使用的打开密码。'}}
    pages={'type':'string','description':'空值表示全部页；文档页码从 1 开始，例如 1,3-5。'}
    region={k:{'type':'number','format':'float','description':'PDF 默认用户空间；四项必须同时提供。'} for k in ['x','y','width','height']}
    fields={
     'API01':({'pages':pages,'preserveParagraphs':{'type':'boolean','default':True}},[]),
     'API02':({'pages':pages,'pattern':{'type':'string','minLength':1,'description':'RE2/J 支持的正则表达式。'},'flags':{'type':'integer','default':0,'enum':[0,1,2,4,3,5,6,7],'description':'1 忽略大小写，2 点匹配换行，4 多行；支持按位组合。'}},['pattern']),
     'API03':({},[]),
     'API04':({'pages':pages,**region},[]),
     'API05':({'pages':pages,'tableIndex':{'type':'integer','minimum':0},**region},['tableIndex']),
     'API06':({'format':{'type':'string','enum':['PNG','JPG'],'default':'PNG'}},[]),
     'API07':({'userPassword':{'type':'string','format':'password','writeOnly':True,'description':'留空表示无需密码打开。'},'ownerPassword':{'type':'string','format':'password','minLength':1,'writeOnly':True,'description':'非空且必须不同于用户密码。'},'keyLength':{'type':'integer','enum':[128,256],'default':256},**{k:{'type':'boolean','default':True} for k in ['allowPrint','allowCopy','allowModify']}},['ownerPassword']),
     'API08':({'certificate':{**binary,'description':'最大 5 MiB 的 PKCS#12 容器。'},'certificatePassword':{'type':'string','format':'password','writeOnly':True},'alias':string,'reason':string,'location':string},['certificate','certificatePassword']),
     'API09':({'trustCertificates':{**binary,'description':'可选 X.509 PEM/DER 信任证书，最大 5 MiB。'}},[]),
     'API10':({'confirmRemoval':{'type':'boolean','enum':[True],'description':'明确确认移除副本中全部签名。'}},['confirmRemoval']),
     'API11':({'type':{'type':'string','enum':['TEXT','IMAGE']},'pages':pages,'text':{'type':'string','minLength':1,'maxLength':512},'image':{**binary,'description':'type=IMAGE 时必填，PNG/JPG，最大 20 MiB、4000 万像素。'},'x':{'type':'number','default':60},'y':{'type':'number','default':60},'fontSize':{'type':'number','minimum':0,'exclusiveMinimum':True,'maximum':500,'default':28},'rgb':{'type':'integer','minimum':0,'maximum':16777215,'default':3355443},'width':{'type':'number','minimum':0,'exclusiveMinimum':True,'maximum':10000,'default':160},'height':{'type':'number','minimum':0,'exclusiveMinimum':True,'maximum':10000,'default':100},'angle':{'type':'number','minimum':-360,'maximum':360,'default':30},'opacity':{'type':'number','minimum':0,'maximum':1,'default':0.3}},['type']),
    }
    paths={}
    for code,path,title,reqs,wnd,op,kind in INTERFACES:
        additional,required=fields[code]
        req_ids=reqs.split('/')
        tag='内容提取' if code in ['API01','API02','API04','API05','API06'] else '文档信息' if code=='API03' else '安全与修改'
        mime={'pdf':'application/pdf','zip':'application/zip','csv':'text/csv'}.get(kind,'application/json')
        schema=binary if kind in ['pdf','zip','csv'] else ref(kind)
        description='所有参数均在 multipart 正文中传递。'+ ('TEXT 必填 text；IMAGE 必填 image；两类水印共同使用页码、位置、角度和透明度。' if code=='API11' else '')
        success={'description':'处理成功。','content':{mime:{'schema':schema}}}
        if kind in ['pdf','zip','csv']:
            success['headers']={'Content-Disposition':{'description':'attachment 及安全文件名。','schema':string}}
        if code=='API06':
            success['description']='ZIP 包包含 manifest.json、原始流及可转换的图片；清单模型见 ImageManifest。'
            success['headers'].update({k:{'schema':{'type':'integer','minimum':0},'description':v} for k,v in [('X-Images-Total','图片资源数量。'),('X-Images-Exported','成功转换的图片资源数量。')]})
        responses={'200':success}
        for status in ['400','403','404','413','415','422','429','500','503','504']:
            responses[status]={'description':'处理错误，具体类别见 code 和 message。','content':{'application/json':{'schema':ref('Error')}}}
        paths['/api/v1/pdf'+path]={'post':{'tags':[tag],'operationId':op,'summary':code+' '+title,'description':description,'x-requirements':['FR1',*req_ids,'ERR01'],'x-wnd':wnd,'requestBody':{'required':True,'content':{'multipart/form-data':{'schema':obj({**common,**additional},['file',*required])}}},'responses':responses}}
    spec={'openapi':'3.0.3','info':{'title':'PDF 文档处理核心接口设计','version':'1.0.0','description':'分页文本、表格、图片、文档信息、加密、签名与水印操作。接口字段与 Part A 需求编号关联。'},'servers':[{'url':'http://127.0.0.1:8080','description':'本机应用接口'}],'tags':[{'name':x} for x in ['内容提取','文档信息','安全与修改']],'paths':paths,'components':{'schemas':schemas}}
    (out/'openapi.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
    (out/'swagger/contract.js').write_text('window.PDF_CORE_OPENAPI = '+json.dumps(spec,ensure_ascii=False)+';\n',encoding='utf-8')
    (out/'swagger/index.html').write_text('''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PDF 核心接口设计</title><link rel="icon" href="data:,"><link rel="stylesheet" href="vendor/swagger-ui.css"><style>body{margin:0;background:#fff}header{padding:18px 32px;border-bottom:1px solid #ccc;font:16px sans-serif}header a{color:#222;margin-right:25px}.swagger-ui .topbar{display:none}.swagger-ui .info{margin:24px 0}.swagger-ui .opblock{box-shadow:none}.swagger-ui .opblock.opblock-post{border-color:#555;background:#fafafa}.swagger-ui .opblock.opblock-post .opblock-summary-method{background:#333}</style></head><body><header><a href="../index.html">Part A 文档与图表</a><a href="../openapi.json">OpenAPI JSON</a><span>PDF 文档处理核心接口设计</span></header><main id="swagger-ui"></main><script src="vendor/swagger-ui-bundle.js"></script><script src="contract.js"></script><script>window.ui=SwaggerUIBundle({spec:window.PDF_CORE_OPENAPI,dom_id:'#swagger-ui',deepLinking:true,docExpansion:'list',defaultModelsExpandDepth:0,validatorUrl:null,persistAuthorization:false,displayRequestDuration:true});</script></body></html>''',encoding='utf-8')
    return spec
