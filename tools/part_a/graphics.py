"""生成可编辑 SVG 和同源 PNG 图。"""
from pathlib import Path
from html import escape
from PIL import Image, ImageDraw, ImageFont
import json, math

FONT = 'C:/Windows/Fonts/simhei.ttf'
BODY = 'C:/Windows/Fonts/simsun.ttc'

class Figure:
    def __init__(self, name, title, w=1400, h=1000):
        self.name,self.title,self.w,self.h=name,title,w,h
        self.image=Image.new('RGB',(w,h),'white'); self.d=ImageDraw.Draw(self.image)
        self.svg=[];self.nodes={};self.edges=[]
    def text(self,x,y,value,size=30,anchor='mm',background=False):
        f=ImageFont.truetype(FONT,size); lines=value.split('\n'); gap=8
        boxes=[self.d.textbbox((0,0),line,font=f) for line in lines]
        widths=[b[2]-b[0] for b in boxes]; height=len(lines)*(size+gap)-gap
        if background:
            bw=max(widths,default=0)+18;bh=height+10
            self.d.rectangle((x-bw/2,y-bh/2,x+bw/2,y+bh/2),fill='white')
            self.svg.append(f'<rect x="{x-bw/2}" y="{y-bh/2}" width="{bw}" height="{bh}" fill="white"/>')
        top=y-height/2
        for i,line in enumerate(lines):
            yy=top+i*(size+gap)
            self.d.text((x,yy),line,font=f,fill='black',anchor='mt')
            self.svg.append(f'<text x="{x}" y="{yy+size*.86}" text-anchor="middle" font-family="SimHei,Microsoft YaHei,sans-serif" font-size="{size}" fill="black">{escape(line)}</text>')
    def node(self,id,text,x,y,w=350,h=110,kind='process'):
        a,b=x-w/2,y-h/2
        self.nodes[id]={'id':id,'label':text,'x':x,'y':y,'width':w,'height':h,'kind':kind}
        self.svg.append(f'<g id="{escape(id)}"><title>{escape(text)}</title>')
        if kind=='process':
            self.d.rounded_rectangle((a,b,a+w,b+h),radius=20,outline='black',width=3)
            self.svg.append(f'<rect x="{a}" y="{b}" width="{w}" height="{h}" rx="20" fill="white" stroke="black" stroke-width="3"/>')
        else:
            self.d.rectangle((a,b,a+w,b+h),outline='black',width=3)
            self.svg.append(f'<rect x="{a}" y="{b}" width="{w}" height="{h}" fill="white" stroke="black" stroke-width="3"/>')
            if kind=='store':
                self.d.line((a+12,b,a+12,b+h),fill='black',width=2)
                self.svg.append(f'<path d="M {a+12} {b} V {b+h}" stroke="black" fill="none" stroke-width="2"/>')
        self.text(x,y,text,30 if w>=300 else 27)
        self.svg.append('</g>')
    def edge(self,src,dst,label,points,label_at=None,size=25):
        self.edges.append({'from':src,'to':dst,'label':label,'points':points})
        self.d.line(points,fill='black',width=3,joint='curve')
        p,q=points[-2:];angle=math.atan2(q[1]-p[1],q[0]-p[0]);L=14
        arr=[q,(q[0]-L*math.cos(angle-.45),q[1]-L*math.sin(angle-.45)),(q[0]-L*math.cos(angle+.45),q[1]-L*math.sin(angle+.45))]
        self.d.polygon(arr,fill='black')
        self.svg.append('<polyline points="'+ ' '.join(f'{x},{y}' for x,y in points)+'" fill="none" stroke="black" stroke-width="3" marker-end="url(#arrow)"/>')
        if label:
            x,y=label_at or ((points[0][0]+points[-1][0])/2,(points[0][1]+points[-1][1])/2)
            self.text(x,y,label,size,background=True)
    def save(self,out):
        self.image.save(out/(self.name+'.png'))
        s=f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}"><title>{escape(self.title)}</title><defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto"><path d="M0 0 L10 5 L0 10 Z" fill="black"/></marker></defs><rect width="100%" height="100%" fill="white"/>'+''.join(self.svg)+'</svg>'
        (out/(self.name+'.svg')).write_text(s,encoding='utf-8')
        (out/(self.name+'.json')).write_text(json.dumps({'id':self.name,'title':self.title,'nodes':list(self.nodes.values()),'edges':self.edges},ensure_ascii=False,indent=2),encoding='utf-8')
        return {'id':self.name,'title':self.title,'width':self.w,'height':self.h}

def build(out):
    out.mkdir(parents=True,exist_ok=True); result=[]
    f=Figure('01-context','系统上下文图',1400,660)
    f.node('E1','E1 文档使用者',240,330,360,150,'external')
    f.node('P0','P0 PDF 文档处理系统',1000,330,560,230)
    f.edge('E1','P0','PDF 与打开凭据\n操作参数及证书或水印素材',[(420,285),(720,285)],(570,200))
    f.edge('P0','E1','提取信息与处理副本\n下载结果及状态或错误',[(720,380),(420,380)],(570,470))
    f.text(700,605,'系统处理上传副本，用户通过浏览器保存结果。',28)
    result.append(f.save(out))

    f=Figure('02-level1','一级数据流图',1400,1510)
    f.node('E1','E1 文档使用者',700,65,420,90,'external')
    f.node('P1','P1 接收与校验输入',700,235,430,105)
    f.node('D1','D1 临时工作区',205,400,350,105,'store')
    f.node('P2','P2 选择处理与范围',700,440,430,105)
    f.node('P3','P3 文本与文档信息\nFR2—FR7、FR9、页数',230,700,410,145)
    f.node('P4','P4 表格与图片\nFR10—FR12',700,700,400,145)
    f.node('P5','P5 安全处理与水印\nFR13—FR19、WM01/02',1170,700,410,145)
    f.node('P6','P6 组装结果与状态',700,1025,430,105)
    f.node('D2','D2 私有对象存储',1170,1025,380,105,'store')
    f.node('D3','D3 执行与文件记录',220,1025,380,105,'store')
    f.node('P7','P7 授权交付与清理',700,1245,430,105)
    f.node('E1b','E1 文档使用者',700,1440,420,90,'external')
    f.edge('E1','P1','PDF、凭据、参数与素材',[(700,110),(700,182)],(700,146))
    f.edge('P1','D1','暂存输入副本',[(485,235),(205,235),(205,347)],(340,235))
    f.edge('P1','P2','已校验操作及参数',[(700,288),(700,387)],(700,333))
    f.edge('D1','P2','工作副本',[(380,400),(430,400),(430,440),(485,440)],(420,370))
    for id,x,label in [('P3',230,'提取范围及输入'),('P4',700,'提取范围及格式'),('P5',1170,'PDF、安全策略及素材')]:
        f.edge('P2',id,label,[(700,493),(700,555),(x,555),(x,627)],(x,580))
    for id,x,label in [('P3',230,'文字与文档信息'),('P4',700,'表格或图片结果'),('P5',1170,'处理副本与诊断')]:
        pts=[(x,773),(x,875),(700,875),(700,972)] if x!=700 else [(700,773),(700,972)]
        f.edge(id,'P6',label,pts,(x,820))
    f.edge('P6','D2','结果对象',[(915,1025),(980,1025)],(951,986),23)
    f.edge('P6','D3','归属与执行状态',[(485,1025),(410,1025)],(448,1100),23)
    f.edge('D3','P7','归属与对象清单',[(220,1078),(220,1170),(485,1170),(485,1245)],(340,1135),23)
    f.edge('P1','P6','输入校验错误',[(915,235),(1387,235),(1387,925),(820,925),(820,972)],(1150,190),23)
    f.edge('P6','P7','结果引用或空结果\n及错误状态',[(700,1078),(700,1192)],(700,1138))
    f.edge('D2','P7','已授权结果内容',[(1170,1078),(1170,1245),(915,1245)],(1125,1192))
    f.edge('P7','D1','到期及任务清理',[(485,1245),(20,1245),(20,400),(30,400)],(200,1330))
    f.edge('P7','E1b','提取结果、处理副本\n下载及状态或错误',[(700,1298),(700,1395)],(700,1347))
    result.append(f.save(out))

    def detail(name,title,parent,children,store='规范化页文本与几何数据'):
        n=len(children);height=240+n*210
        f=Figure(name,title,1400,height)
        f.node('IN',f'{parent} 父过程输入',280,80,470,100,'boundary')
        f.node('OUT',f'{parent} 父过程输出',1120,80,470,100,'boundary')
        for i,(id,txt,inp,output) in enumerate(children):
            y=240+i*210
            f.node(id,id+' '+txt,700,y,520,115)
            f.edge('IN',id,inp,[(280,130),(80,130),(80,y),(440,y)],(280,y-62))
            f.edge(id,'OUT',output,[(960,y),(1320,y),(1320,130),(1120,130)],(1120,y+61))
        if parent=='P3':
            f.edge('P3.1','P3.2','规范化页文本',[(700,297.5),(700,392.5)],(700,345),24)
        if parent=='P4':
            f.edge('P4.1','P4.2','二维单元格',[(700,297.5),(700,392.5)],(700,345),24)
        f.text(700,height-44,store,27)
        return f.save(out)
    result.append(detail('03-text-info','文本与文档信息二级数据流图','P3',[
      ('P3.1','按页组织文字、文本块与段落\nFR2、FR3、FR5','PDF、页码与段落选项','页文本、块、段落及诊断'),
      ('P3.2','执行 RE2/J 正则匹配\nFR4','表达式及标记','匹配文字、偏移与位置'),
      ('P3.3','读取书签、元数据及批注\nFR6、FR7、FR9、PAGE01','PDF 目录及页面信息','页数、信息、目录及批注'),
    ],'同一规范化文字与坐标映射用于提取、匹配和定位。'))
    result.append(detail('04-tables-images','表格与图片二级数据流图','P4',[
      ('P4.1','检测网格并分配单元格文字\nFR10','PDF、页码及可选区域','网格边界与二维单元格'),
      ('P4.2','呈现交叉表并安全编码 CSV\nFR11','所选表格索引','原行列结果或 CSV'),
      ('P4.3','遍历图片并导出原始流与转换图\nFR12','PDF 图片资源与目标格式','图片包、清单或无图片错误'),
    ],'交叉表保留提取后的二维关系，不进行透视汇总。'))
    result.append(detail('05-security','加密与签名二级数据流图','P5',[
      ('P5.1','设置 AES 和权限\nFR13—FR16','PDF、原凭据及新策略','加密副本或权限错误'),
      ('P5.2','创建证书 CMS 签名\nFR17','PDF、临时证书及签署参数','签署副本或证书错误'),
      ('P5.3','分项验证完整性与证书\nFR18','PDF 及可选信任证书','完整性、证书与修订结论'),
      ('P5.4','确认后移除签名结构\nFR19','未加密 PDF 与明确确认','无签名副本或拒绝原因'),
    ],'口令和私钥仅在本次受控处理窗口传递，不进入持久记录。'))
    result.append(detail('06-watermark','水印二级数据流图','P5',[
      ('P5.5','追加文字水印\nWM01','PDF、页码、文字及放置参数','包含文字水印的新 PDF'),
      ('P5.6','解码并等比放置图片水印\nWM02','PDF、页码、图片及放置参数','包含图片水印的新 PDF'),
    ],'两类水印共同校验页码、坐标、透明度和文档修改权限。'))

    f=Figure('07-wnd','窗口与面板导航图',1400,1490)
    f.node('W00','W00 工作台与 PDF 选择',700,75,600,100)
    for i,(id,name) in enumerate([('W01','文本提取 / 正则搜索'),('W02','表格 / 交叉表 / CSV'),('W03','图片格式与导出'),('W04','密码、权限与加密强度'),('W05','签署 / 验证 / 移除'),('W06','文字 / 图片水印'),('W07','页数、元数据、目录与批注')]):
        col=i%2;row=i//2;x=370+col*660;y=280+row*190
        f.node(id,id+' '+name,x,y,600,105)
        pts=[(700,125),(700,160),(40 if col==0 else 1360,160),(40 if col==0 else 1360,y),(70 if col==0 else 1330,y)]
        f.edge('W00',id,'',pts)
    f.text(700,1420,'工具切换保留当前 PDF；子模式属于同一参数面板。',28)
    f.node('W08','W08 处理状态',260,1100,400,100)
    f.node('W09','W09 结果与下载',720,1100,400,100)
    f.node('W10','W10 错误与重新输入',1170,1100,420,100)
    for i,id in enumerate(['W01','W02','W03','W04','W05','W06','W07']):
        x=370+(i%2)*660;y=280+(i//2)*190;bus=685 if i%2==0 else 1345
        f.edge(id,'W08','',[(x+300,y+20),(bus,y+20),(bus,960),(260,960),(260,1050)])
    f.text(700,1005,'提交当前工具参数',26)
    f.edge('W08','W09','完成 / 空结果',[(460,1100),(520,1100)],(490,1035))
    f.edge('W08','W10','失败 / 缺少凭据',[(260,1150),(260,1280),(1170,1280),(1170,1150)],(730,1280))
    f.edge('W10','W00','修正参数或更换文件',[(1380,1100),(1390,1100),(1390,75),(1000,75)],(1160,920))
    f.text(700,1360,'图中的连线表示界面导航或状态转换，不表示数据流。',28)
    result.append(f.save(out))

    f=Figure('08-architecture','逻辑与本机部署架构',1400,1110)
    f.node('BROWSER','浏览器\nVue 3 / TypeScript / PDF.js',700,95,760,130,'external')
    f.node('APP','Windows Java 主服务\n接口、授权、任务协调与页面资源',700,350,760,150)
    f.node('EXEC','受限 Java PDF 子进程\nPDFBox / Bouncy Castle / RE2/J',700,620,760,145)
    f.node('PG','Docker PostgreSQL\n业务、会话和执行记录',320,940,580,160,'store')
    f.node('S3','Docker SeaweedFS\n私有 PDF 与处理结果',1080,940,580,160,'store')
    f.edge('BROWSER','APP','同源 HTTP；公网配置 HTTPS',[(700,160),(700,275)],(700,216))
    f.edge('APP','EXEC','有界执行与受控输入',[(700,425),(700,547)],(700,485))
    f.edge('EXEC','APP','结果、状态与诊断',[(1080,620),(1260,620),(1260,350),(1080,350)],(1220,490))
    f.edge('APP','PG','JDBC',[(320,350),(160,350),(160,790),(320,790),(320,860)],(160,735))
    f.edge('APP','S3','S3 接口',[(1080,425),(1180,425),(1180,795),(1080,795),(1080,860)],(1140,735))
    f.text(700,1070,'本机直接运行应用；Docker 仅承载中间件。',29)
    result.append(f.save(out))

    for mode in ['text','signature']:
        f=Figure('09-ui-'+mode,'文本工具界面原型' if mode=='text' else '验签界面原型',1600,1110)
        d=f.d
        def rect(x,y,w,h,label='',size=27):
            f.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="white" stroke="black" stroke-width="2"/>');d.rectangle((x,y,x+w,y+h),outline='black',width=2)
            if label:f.text(x+w/2,y+h/2,label,size)
        rect(12,12,1576,1086);rect(12,12,270,1086)
        f.text(145,66,'PDF 工作台',34)
        for i,t in enumerate(['文本提取','表格提取','图片提取','文档加密','数字签名','文档水印','文档信息']):rect(34,140+i*108,224,76,t,29)
        f.text(940,67,'文本提取与属性' if mode=='text' else '数字签名验证',37)
        rect(312,125,1240,78,'当前文档    文档名称.pdf        20 页        更换文件',28)
        rect(312,232,670,800);f.text(648,278,'文档预览    1 / 20    100%',28)
        rect(386,330,520,620);f.text(646,464,'PDF 页面预览',32)
        f.text(646,550,'文字与图像位于原页坐标',26)
        f.text(646,620,'选择结果条目后定位区域',26)
        rect(1010,232,542,800)
        if mode=='text':
            f.text(1280,278,'处理参数',31)
            rect(1040,322,482,70,'页码范围    全部页或 1,3-5')
            rect(1040,412,482,70,'文本提取    /    正则搜索')
            rect(1040,502,482,70,'保留段落结构')
            rect(1040,592,482,70,'提取文本',30)
            f.text(1280,720,'处理结果',31)
            f.text(1280,775,'文本    /    文本块属性',28)
            f.text(1280,838,'页码、文字、字体、字号、颜色',24)
            f.text(1280,891,'X、Y、宽度、高度与原页定位',24)
            f.text(1280,976,'结果将在处理完成后显示',25)
        else:
            f.text(1280,278,'处理参数',31)
            rect(1040,322,482,70,'签署    /    验证    /    移除')
            rect(1040,412,482,70,'选择可选信任证书')
            rect(1040,502,482,70,'验证签名',30)
            f.text(1280,650,'验证结果',31)
            for y,t in [(710,'签名完整性    有效 / 无效 / 无法验证'),(765,'签后修订    有 / 无 / 未知'),(820,'证书有效期\n有效 / 过期 / 尚未生效 / 未知'),(875,'证书信任    可信 / 不可信 / 未评估'),(930,'证书主体、签发者与有效时间')]:f.text(1280,y,t,24)
            f.text(1280,990,'各项结论分别呈现',25)
        result.append(f.save(out))
    (out/'manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    return result
