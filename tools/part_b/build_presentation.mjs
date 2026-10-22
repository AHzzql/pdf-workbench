/** Build the 12-slide Chinese Part B classroom demonstration. */
import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const ROOT=path.resolve(import.meta.dirname,'../..');
const DEPS=process.env.CODEX_RUNTIME_NODE_MODULES;
if(!DEPS)throw new Error('CODEX_RUNTIME_NODE_MODULES must point to the bundled workspace node_modules');
const {Presentation,PresentationFile}=await import(pathToFileURL(path.join(DEPS,'@oai/artifact-tool/dist/artifact_tool.mjs')).href);
const {finalizePresentation}=await import(pathToFileURL('C:/Users/38937/.codex/plugins/cache/openai-primary-runtime/presentations/26.1020.11348/skills/presentations/container_tools/artifact_tool_utils.mjs').href);
const skill='C:/Users/38937/.codex/plugins/cache/openai-primary-runtime/presentations/26.1020.11348/skills/presentations';
const PY=process.env.CODEX_RUNTIME_PYTHON;
if(!PY)throw new Error('CODEX_RUNTIME_PYTHON must point to the bundled workspace Python');
const privateDir=path.join(ROOT,'backend/target/part-b-presentation');
const outDir=path.join(ROOT,'docs/part-b/presentation');
await fs.mkdir(privateDir,{recursive:true});await fs.mkdir(outDir,{recursive:true});
const C={ink:'#102A38',deep:'#0B2A39',teal:'#087F8C',pale:'#EAF2F0',paper:'#F5F3ED',white:'#FFFFFF',muted:'#5C6C70',line:'#CFDCDD',gold:'#D4A65C'};
const FONT='Microsoft YaHei';
const deck=Presentation.create({slideSize:{width:1280,height:720}});
function shape(slide,x,y,w,h,fill='none',stroke='none',kind='rect'){
 return slide.shapes.add({geometry:kind,position:{left:x,top:y,width:w,height:h},fill,line:{style:'solid',fill:stroke,width:stroke==='none'?0:1}});
}
function txt(slide,text,x,y,w,h,size=30,color=C.ink,bold=false){
 const s=shape(slide,x,y,w,h);s.text=text;s.text.style={fontFamily:FONT,fontSize:size,color,bold};s.text.insets=0;return s;
}
function shell(title,num,dark=false){
 const s=deck.slides.add();s.background.fill=dark?C.deep:C.paper;
 shape(s,0,0,16,720,C.teal);txt(s,'PDF EXTRACTOR',72,42,300,30,16,dark?C.pale:C.teal,true);
 txt(s,String(num).padStart(2,'0'),1160,42,56,30,16,dark?C.pale:C.teal,true);
 txt(s,title,72,104,1120,80,43,dark?C.white:C.ink,true);
 shape(s,72,193,72,5,C.teal);
 txt(s,'PDF Extractor · Part B',72,673,380,22,13,dark?'#B1C6C9':C.muted);
 return s;
}
function item(s,y,label,detail,w=330,x=845){
 shape(s,x,y+5,9,9,C.teal);txt(s,label,x+22,y-4,w-22,34,24,C.ink,true);
 txt(s,detail,x+22,y+33,w-22,80,18,C.muted);
}
function banner(s,value,description,x,y,w=260){
 shape(s,x,y,w,160,C.pale);txt(s,value,x+22,y+16,w-44,70,52,C.teal,true);
 txt(s,description,x+22,y+93,w-44,50,18,C.ink);
}
async function image(s,relative,x,y,w,h){
 const name=path.join(ROOT,relative);const bytes=await fs.readFile(name);const blob=bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength);s.images.add({blob,contentType:'image/png',alt:relative,fit:'contain',position:{left:x,top:y,width:w,height:h}});
}
function note(s,text,source){s.speakerNotes.text=text+'\n依据：'+source;}
// 1. Introduction
{
 const s=deck.slides.add();s.background.fill=C.deep;
 shape(s,0,0,24,720,C.teal);shape(s,70,93,80,5,C.gold);
 txt(s,'PDF Extractor',72,151,920,110,67,C.white,true);
 txt(s,'PDF 文档提取、保护与修改',72,281,1030,88,37,C.pale);
 txt(s,'功能原型与实际测试',72,402,700,50,25,'#B1C6C9');
 shape(s,72,570,1130,1,'#52717A');txt(s,'Part B · 课堂演示',72,596,680,42,23,C.white);
 txt(s,'12 页',1110,596,100,42,20,C.gold,true);
 note(s,'说明演示范围：文本、表格、图片、加密、签名和水印。','docs/part-b/PartB_Submission.docx §1.1');
}
// 2. Workbench and navigation
{
 const s=shell('一个文档，七类操作',2);await image(s,'docs/part-b/diagrams/wnd.png',55,220,740,415);
 item(s,247,'W00 → W01','选择 PDF，预览并确定页码。',365);
 item(s,370,'W02 → W08','在当前文档切换七类工具。',365);
 item(s,493,'W09 → W10','查看结果或错误，修正后重试。',365);
 note(s,'选择 api-sample.pdf，切换文本、表格、图片，确认原文件无需重复上传。','docs/part-b/diagrams/wnd.json；frontend/src/router/index.ts');
}
// 3. Architecture
{
 const s=shell('基础操作采用同步请求',3);await image(s,'docs/part-b/diagrams/architecture.png',58,227,790,414);
 item(s,252,'浏览器 + Nginx','静态文件和 API 使用同源入口。',335);
 item(s,379,'Windows Java Web','核心 PDF 运算仍在 Web JVM 内。',335);
 item(s,506,'后台服务','Worker 与持久任务独立运行。',335);
 note(s,'区分基础同步 PDF 工具与独立 Worker。PostgreSQL、Redis、RabbitMQ、Elasticsearch 为共享支撑组件，不称核心正则检索依赖 Elasticsearch。','docs/part-b/PartB_Submission.docx §1.2.1；service/PdfService.java');
}
// 4. Text
{
 const s=shell('文本、样式、位置与检索',4);await image(s,'docs/part-b/evidence/02-text.png',42,222,790,438);
 item(s,256,'按页提取','文本层、文字块、字体及坐标。',320);
 item(s,392,'正则检索','返回命中页、偏移与位置。',320);
 item(s,528,'实测用例','TC01、TC02；异常表达式返回 400。',320);
 note(s,'在文本面板运行提取并展示 Alpha/42，再按 report 检索。','docs/part-b/evidence/core-junit.xml；docs/part-b/evidence/02-text.png');
}
// 5. Tables
{
 const s=shell('规则网格转为可核对的行列',5);await image(s,'docs/part-b/evidence/03-table.png',42,222,790,438);
 item(s,256,'规则网格','收集线段，分离表格并保留空值。',320);
 item(s,392,'CSV 导出','核对 Alpha、42 与 UTF-8 内容。',320);
 item(s,528,'适用范围','不自动推断无边框表格。',320);
 note(s,'使用 api-sample.pdf 在表格面板核对行列，点击导出 CSV。','PdfTableUtilsTest；docs/part-b/evidence/03-table.png');
}
// 6. Images
{
 const s=shell('原始流与转换结果分别记录',6);await image(s,'docs/part-b/evidence/core-partial-images.png',42,222,790,438);
 item(s,256,'资源身份','同一图片重复绘制仍保留出现位置。',320);
 item(s,392,'ZIP 清单','原始流、PNG/JPG 与来源信息。',320);
 item(s,528,'部分转换','RAW_ONLY 显示诊断，不伪装成功。',320);
 note(s,'演示部分图片不可转换时的反馈，再查看 ZIP 的清单和可解码图片。','PdfImageUtilsTest；PdfApiTest.reportsPartialImageExportsWithRawFilesAndDiagnostics');
}
// 7. Encryption
{
 const s=shell('加密与权限独立设置',7);banner(s,'128 / 256','AES 密钥长度',90,257,285);
 banner(s,'3','打印 · 复制 · 修改开关',415,257,285);
 banner(s,'16','参数化组合通过',740,257,285);
 shape(s,90,472,1030,112,C.white,C.line);txt(s,'源文件保持不变；输出加密副本。普通口令与所有者口令具有不同权限。',118,496,970,76,25,C.ink);
 note(s,'展示用户口令、所有者口令及三个权限开关；打开输出副本核对权限。','PdfEncryptionUtilsTest.encryptsWithIndependentPermissions；Part B TC05');
}
// 8. Signatures
{
 const s=shell('签名结论需要分项解释',8);await image(s,'docs/part-b/evidence/04-signature.png',42,222,790,438);
 item(s,250,'签署','测试证书输出新的签名副本。',320);
 item(s,383,'验证','完整性、信任、有效期与修订分列。',320);
 item(s,516,'移除','需明确确认，输出保留正文。',320);
 note(s,'对 signed.pdf 验签，说明 VALID 与 TRUSTED 含义不同，再确认移除并查看 removed.pdf。','PdfSignatureUtilsTest；docs/part-b/evidence/signed.pdf；removed.pdf');
}
// 9. Watermark
{
 const s=shell('指定页面添加文字或图片水印',9);await image(s,'backend/target/verification/watermark-chinese.png',65,225,645,420);
 item(s,250,'页范围','只修改选定页；其他页内容保持。',430);
 item(s,383,'文字与图像','支持中文文字、角度和透明度。',430);
 item(s,516,'原件保护','签名输入拒绝再次修改。',430);
 note(s,'演示中文文字水印，比较输入与输出；说明另一页未新增水印。','PdfWatermarkUtilsTest.writesChineseOnSelectedPageAndPreservesOtherPage');
}
// 10. Errors
{
 const s=shell('错误反馈与业务结果分开',10);await image(s,'docs/part-b/evidence/core-unknown-revision.png',42,222,790,438);
 item(s,247,'400 / 403','页码或表达式错误；密码错误。',330);
 item(s,374,'413 / 429','超出容量；并发槽位占用。',330);
 item(s,501,'验签状态','无法判定修订，不等于请求失败。',330);
 note(s,'分别展示非法页码、缺少密码、无图片结果和未知签名类型。','PdfApiTest.distinguishesBusinessErrorsAndReturnsCoreProperties；docs/part-b/evidence/core-unknown-revision.png');
}
// 11. Evidence
{
 const s=shell('测试记录可以回溯到用例',11,true);
 shape(s,82,237,535,227,'#153B4A');shape(s,664,237,535,227,'#153B4A');
 txt(s,'93',112,255,230,130,94,C.white,true);txt(s,'JUnit 实例 · 失败 0',112,387,460,45,23,C.pale);
 txt(s,'8',694,255,230,130,94,C.white,true);txt(s,'浏览器用例 · 失败 0',694,387,460,45,23,C.pale);
 txt(s,'14 条完整测试记录  /  需求 → 实现 → 用例 → 证据',82,532,1110,83,28,C.white);
 note(s,'展示 core-junit.xml 中对应方法、14 条记录、实际截图与证据 SHA-256。','docs/part-b/evidence/junit-summary.txt；browser-summary.txt；manifest.json');
}
// 12. Runtime and scope
{
 const s=shell('本机运行入口与适用边界',12,true);
 const cols=[['前端','Vue 3 · Vite 构建文件'],['网关','Nginx · 同源 5173'],['后端','Spring Boot · Java 21'],['支撑','PostgreSQL / Redis / RabbitMQ / Elasticsearch / S3']];
 for(let i=0;i<cols.length;i++){
   const x=78+i*292;shape(s,x,235,268,250,'#153B4A');shape(s,x,235,268,8,C.teal);
   txt(s,cols[i][0],x+22,268,222,51,29,C.white,true);txt(s,cols[i][1],x+22,341,222,104,21,C.pale);
 }
 txt(s,'单 PDF ≤50 MiB · ≤200 页 · 同时处理 2 项 · 输出 ≤200 MiB',82,532,1110,65,24,C.white);
 txt(s,'公网部署仍需 TLS、可信代理、最小权限与配套备份。',82,602,1110,45,19,'#B1C6C9');
 note(s,'说明本机启动顺序与对公网部署的独立配置要求；现场仅使用本机服务。','deploy/README.md；backend/src/main/resources/application.yml；docs/part-b/PartB_Submission.docx §1.6');
}
if(deck.slides.items.length!==12)throw new Error('Expected 12 slides');
const imageDirs=path.join(privateDir,'pages');await fs.mkdir(imageDirs,{recursive:true});
for(let i=0;i<12;i++){
 const blob=await deck.export({slide:deck.slides.items[i],format:'png',scale:1});
 await fs.writeFile(path.join(imageDirs,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await blob.arrayBuffer()));
}
const candidatePath=path.join(privateDir,'candidate.pptx');await(await PresentationFile.exportPptx(deck)).save(candidatePath);
const result=await finalizePresentation({workspaceDir:ROOT,candidatePath,finalPath:path.join(outDir,'PartB_Demonstration.pptx'),pythonExecutable:PY,integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit'],explicitTotalSlideCount:12,fontPolicy:{basis:'design',families:[FONT,'Aptos'],scriptFonts:{ea:FONT}},verifyArtifactToolImport:true,receiptPath:path.join(privateDir,'finalizer.json')});
console.log(JSON.stringify({slides:deck.slides.items.length,validation:result}));
