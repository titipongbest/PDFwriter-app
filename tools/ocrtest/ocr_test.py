"""Thai OCR check: renders Thai text, runs Tesseract (fast vs best), counts tone marks kept."""
import subprocess, sys, os, unicodedata
from PIL import Image, ImageDraw, ImageFont

TEXT = [
    "ผู้ประกอบวิชาชีพกฎหมายแบ่งได้เป็น 2 ประเภท ได้แก่ ผู้ประกอบวิชาชีพกฎหมายที่เกี่ยวข้อง",
    "กับศาลโดยตรง คือ ผู้พิพากษา พนักงานอัยการและทนายความ กับผู้ประกอบวิชาชีพกฎหมาย",
    "ที่ไม่เกี่ยวข้องกับศาลโดยตรง คือ นิติกร นายทหารพระธรรมนูญ เป็นต้น",
    "วิชากฎหมาย เป็นวิชาชีพซึ่งต้องศึกษาอบรมในวิทยาการทางความคิดยิ่งกว่าการใช้มือหรือ",
    "แรงงาน สังคมจำต้องมีกฎหมายเพื่อความอยู่รอดของสังคม การศึกษากฎหมายและการประกอบ",
]
TONES = "่้๊๋"  # ่ ้ ๊ ๋
def tones(s): return sum(s.count(t) for t in TONES)
def marks(s): return sum(1 for c in s if unicodedata.category(c) == "Mn")

font_path = sys.argv[1]
img = Image.new("L", (2000, 80 + 70 * len(TEXT)), 255)
d = ImageDraw.Draw(img)
f = ImageFont.truetype(font_path, 36)
for i, line in enumerate(TEXT):
    d.text((60, 40 + 70 * i), line, font=f, fill=0)
img.save("thai.png")
truth = "\n".join(TEXT)
print("truth: tones", tones(truth), "all marks", marks(truth))
for name in ["fast", "best"]:
    for psm in ["3", "6"]:
        out = subprocess.run(["tesseract", "thai.png", "-", "--tessdata-dir", name, "-l", "tha+eng", "--oem", "1", "--psm", psm],
                             capture_output=True, text=True).stdout
        print(f"== {name} psm{psm}: tones {tones(out)} / {tones(truth)}, marks {marks(out)} / {marks(truth)}")
        print(out.strip()[:400])
