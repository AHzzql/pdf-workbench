"""将 Word 导出的 QA PDF 渲染为逐页图，并提取文字布局。"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import pypdfium2 as pdfium
import json
ROOT=Path(__file__).resolve().parents[2];QA=ROOT/'backend/target/part-a-qa';OUT=QA/'pages';OUT.mkdir(exist_ok=True)
for old in [*OUT.glob('page-*.png'),*OUT.glob('page-*.txt'),*QA.glob('sheet-*.png')]:
    assert old.resolve().is_relative_to(QA.resolve())
    old.unlink()
doc=pdfium.PdfDocument(str(QA/'PartA.pdf'));font=ImageFont.truetype('C:/Windows/Fonts/simhei.ttf',20)
thumbs=[];pages=[]
for i,p in enumerate(doc):
    im=p.render(scale=1.5).to_pil().convert('RGB');im.save(OUT/f'page-{i+1}.png')
    tp=p.get_textpage();t=tp.get_text_range();(OUT/f'page-{i+1}.txt').write_text(t,encoding='utf-8')
    pages.append({'page':i+1,'characters':len(t),'start':t[:100].replace('\r\n',' '),'width':p.get_width(),'height':p.get_height()})
    im.thumbnail((385,550));thumbs.append(im)
for j in range(0,len(thumbs),6):
    sheet=Image.new('RGB',(1230,1190),'#dddddd');d=ImageDraw.Draw(sheet)
    for k,im in enumerate(thumbs[j:j+6]):
        x=15+(k%3)*410;y=15+(k//3)*590;d.text((x,y),f'第 {j+k+1} 页',font=font,fill='black');sheet.paste(im,(x,y+30))
    sheet.save(QA/f'sheet-{j//6+1}.png')
(QA/'page-index.json').write_text(json.dumps(pages,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'pages':len(doc),'sparse':[p for p in pages if p['characters']<100]},ensure_ascii=False))
