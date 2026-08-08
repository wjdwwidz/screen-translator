import zlib, struct, sys
def load(path):
    data=open(path,'rb').read(); pos=8; idat=b''
    while pos<len(data):
        ln=struct.unpack('>I',data[pos:pos+4])[0]; typ=data[pos+4:pos+8]
        c=data[pos+8:pos+8+ln]
        if typ==b'IHDR': w,h,bd,ct=struct.unpack('>IIBB',c[:10])
        elif typ==b'IDAT': idat+=c
        pos+=12+ln
    raw=zlib.decompress(idat); bpp=4; stride=w*bpp
    out=bytearray(w*h*bpp); prev=bytearray(stride); p=0
    for y in range(h):
        f=raw[p]; p+=1; line=bytearray(raw[p:p+stride]); p+=stride
        if f==1:
            for i in range(bpp,stride): line[i]=(line[i]+line[i-bpp])&255
        elif f==2:
            for i in range(stride): line[i]=(line[i]+prev[i])&255
        elif f==3:
            for i in range(stride):
                a=line[i-bpp] if i>=bpp else 0
                line[i]=(line[i]+((a+prev[i])>>1))&255
        elif f==4:
            for i in range(stride):
                a=line[i-bpp] if i>=bpp else 0
                cc=prev[i-bpp] if i>=bpp else 0
                b=prev[i]; pa=abs(b-cc); pb=abs(a-cc); pc=abs(a+b-2*cc)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else cc)
                line[i]=(line[i]+pr)&255
        out[y*stride:(y+1)*stride]=line; prev=line
    return w,h,out

def analyze(path):
    w,h,px=load(path)
    def at(x,y):
        i=(y*w+x)*4; return px[i],px[i+1],px[i+2]
    def iscyan(x,y):
        r,g,b=at(x,y); return r<90 and 120<g<200 and b>200
    runs=[]
    for y in range(h):
        x=0
        while x<w:
            if iscyan(x,y):
                x0=x
                while x<w and iscyan(x,y): x+=1
                if x-x0>60: runs.append((y,x0,x-1))
            else: x+=1
    runs.sort(); boxes=[]; used=set()
    for i,(y1,a1,b1) in enumerate(runs):
        if i in used: continue
        for j in range(i+1,len(runs)):
            if j in used: continue
            y2,a2,b2=runs[j]
            if abs(a1-a2)<=2 and abs(b1-b2)<=2 and 10<y2-y1<400:
                boxes.append((a1,y1,b1,y2)); used.add(i); used.add(j); break
    res=[]
    for (l,t,r,b) in boxes:
        ys=[]
        for y in range(t+3,b-2):
            dark=0
            for x in range(l+3,r-2):
                rr,gg,bb=at(x,y)
                if (rr+gg+bb)/3 < 140 and not (rr<90 and 120<gg<200 and bb>200): dark+=1
            if dark>0: ys.append(y)
        if not ys: continue
        gt,gb=ys[0],ys[-1]
        # skip when ink spans nearly the whole box (icon/photo, not a clean text line)
        if (gb-gt) > (b-t)*0.85: continue
        res.append((l,t,r,b,b-t,gt,gb,(gt+gb)/2-(t+b)/2))
    return res

for path in sys.argv[1:]:
    print("==",path)
    print(f"{'box':<26}{'h':>5}{'ink':>12}{'delta':>8}")
    for l,t,r,b,bh,gt,gb,d in analyze(path):
        print(f"({l},{t},{r},{b})".ljust(26), f"{bh:>4}", f"{gt}..{gb}".rjust(11), f"{d:>+7.1f}")
