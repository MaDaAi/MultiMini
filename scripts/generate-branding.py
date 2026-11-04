"""Create Windows branding assets from the publisher's supplied PNG."""
from pathlib import Path
import argparse
parser=argparse.ArgumentParser();parser.add_argument("--channel",default="Experimental");args=parser.parse_args()
from PIL import Image,ImageDraw,ImageFont
root=Path(__file__).resolve().parents[1]
out=root/'installer/assets';out.mkdir(exist_ok=True)
logo=Image.open(root/'app/src/main/resources/app/multimin/multimini-logo.png').convert('RGBA')
logo.resize((256,256),Image.Resampling.LANCZOS).save(out/'multimini.ico',sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
small=Image.new('RGB',(64,64),'#191d24');small.paste(logo.resize((48,48),Image.Resampling.LANCZOS),(8,8));small.save(out/'wizard-small.png')
banner=Image.new('RGB',(328,628),'#191d24');banner.paste(logo.resize((180,180),Image.Resampling.LANCZOS),(74,112));draw=ImageDraw.Draw(banner)
fontroot=Path('C:/Windows/Fonts')
font=ImageFont.truetype(str(fontroot/'segoeuib.ttf'),32);detail=ImageFont.truetype(str(fontroot/'segoeui.ttf'),20)
draw.text((45,322),'MultiMini',font=font,fill='#eef1f6');draw.text((45,370),args.channel,font=detail,fill='#72a7ff');draw.text((45,545),'Your desktop, layered.',font=detail,fill='#a6afbd');banner.save(out/'wizard.png')
print(out)

terms=(root/'TERMS.md').read_text(encoding='utf-8-sig')
(out/'TERMS.txt').write_text('\n'.join(line.lstrip('#').strip() if line.startswith('#') else line for line in terms.splitlines()),encoding='utf-8-sig')
