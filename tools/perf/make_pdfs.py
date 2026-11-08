"""Generates the test PDFs for the page-loading timing test (not committed: large)."""
import os, sys
os.chdir(sys.argv[1] if len(sys.argv) > 1 else ".")
import random, io
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from PIL import Image, ImageDraw, ImageFilter
random.seed(1)
W,H=A4
# 1) text-heavy, 100 pages, with some vector graphics
c=canvas.Canvas("text.pdf",pagesize=A4)
words="the quick brown fox jumps over lazy dog lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor".split()
for p in range(100):
    c.setFont("Helvetica-Bold",16); c.drawString(50,H-60,f"Chapter {p+1}")
    c.setFont("Times-Roman",10)
    y=H-90
    while y>60:
        c.drawString(50,y," ".join(random.choice(words) for _ in range(16))); y-=13
    for _ in range(20):
        c.setStrokeColorRGB(random.random(),random.random(),random.random()); c.line(random.uniform(0,W),random.uniform(0,H),random.uniform(0,W),random.uniform(0,H))
    c.showPage()
c.save()

# 2) scanned book, 30 pages, 300dpi grayscale JPEG
c=canvas.Canvas("scan.pdf",pagesize=A4)
for p in range(40):
    im=Image.new("L",(1654,2339),235); d=ImageDraw.Draw(im)
    for line in range(88):
        y=150+line*24
        x=150
        while x<1530:
            w=random.randint(20,90); d.rectangle([x,y,x+w,y+15],fill=random.randint(20,70)); x+=w+18
    im=im.filter(ImageFilter.GaussianBlur(1))
    px=im.load()
    for _ in range(60000): px[random.randrange(1654),random.randrange(2339)]=random.randint(150,255)
    b=io.BytesIO(); im.save(b,"JPEG",quality=60); b.seek(0)
    from reportlab.lib.utils import ImageReader
    c.drawImage(ImageReader(b),0,0,W,H); c.showPage()
c.save()
