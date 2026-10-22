"""Render the Word-exported submission PDF for page-by-page review."""
from pathlib import Path
import json,hashlib
import pypdfium2 as pdfium
from PIL import Image,ImageDraw

ROOT=Path(__file__).resolve().parents[2]
QA=ROOT/'backend/target/part-a-submission-qa'
def run():
    pages=QA/'pages';pages.mkdir(parents=True,exist_ok=True)
    document=pdfium.PdfDocument(str(QA/'PartA.pdf'))
    thumbs=[];index=[]
    for i,page in enumerate(document):
        image=page.render(scale=1.5).to_pil().convert('RGB');image.save(pages/f'page-{i+1}.png')
        text=page.get_textpage().get_text_range();(pages/f'page-{i+1}.txt').write_text(text,encoding='utf-8')
        index.append({'page':i+1,'characters':len(text),'start':text[:130]})
        image.thumbnail((440,625));thumbs.append(image)
    for start in range(0,len(thumbs),6):
        sheet=Image.new('RGB',(1380,1340),'#eeeeee');draw=ImageDraw.Draw(sheet)
        for k,image in enumerate(thumbs[start:start+6]):
            x=15+(k%3)*460;y=15+(k//3)*670
            draw.text((x,y),f'Page {start+k+1}',fill='black');sheet.paste(image,(x,y+25))
        sheet.save(QA/f'sheet-{start//6+1}.png')
    (QA/'page-index.json').write_text(json.dumps(index,indent=2),encoding='utf-8')
    (QA/'raster.json').write_text(json.dumps({'pdf_sha256':hashlib.sha256((QA/'PartA.pdf').read_bytes()).hexdigest(),'pages':len(index)}),encoding='utf-8')
    print(json.dumps({'pages':len(index),'sparse':[p for p in index if p['characters']<200]}))
if __name__=='__main__':run()
