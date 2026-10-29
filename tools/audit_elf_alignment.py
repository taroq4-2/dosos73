"""Read-only ELF load-segment alignment audit, including embedded runtime ZIPs."""
import argparse,io,json,struct,zipfile
from pathlib import Path

def inspect(data):
    if not data.startswith(b'\x7fELF'):return None
    bits,order=data[4],data[5]
    endian={1:'<',2:'>'}[order]
    if bits==2:
        phoff=struct.unpack_from(endian+'Q',data,32)[0]
        size,count=struct.unpack_from(endian+'HH',data,54)
        fmt=endian+'IIQQQQQQ'
    elif bits==1:
        phoff=struct.unpack_from(endian+'I',data,28)[0]
        size,count=struct.unpack_from(endian+'HH',data,42)
        fmt=endian+'IIIIIIII'
    else:raise ValueError('Invalid ELF class')
    loads=[]
    for i in range(count):
        p=struct.unpack_from(fmt,data,phoff+i*size)
        if p[0]!=1:continue
        offset,address,alignment=(p[2],p[3],p[7]) if bits==2 else (p[1],p[2],p[7])
        loads.append({'alignment':alignment,'congruent_16k':offset%16384==address%16384})
    return {'bits':bits*32,'loads':loads,'passes_16k_segment_gate':bool(loads) and all(x['alignment']>=16384 and x['congruent_16k'] for x in loads)}

def scan(z,prefix,results):
    for entry in z.infolist():
        if entry.is_dir():continue
        data=z.read(entry)
        elf=inspect(data)
        name=prefix+entry.filename
        if elf is not None:results.append({'path':name,**elf})
        elif entry.filename.endswith('.zip.so'):
            with zipfile.ZipFile(io.BytesIO(data)) as nested:scan(nested,name+'!/',results)

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('apk',type=Path);p.add_argument('report',type=Path)
a=p.parse_args();results=[]
with zipfile.ZipFile(a.apk) as z:scan(z,'',results)
failed=[x['path'] for x in results if x['bits']==64 and not x['passes_16k_segment_gate']]
legacy=[x['path'] for x in results if x['bits']==32 and not x['passes_16k_segment_gate']]
a.report.write_text(json.dumps({'scope':'ELF PT_LOAD only; not runtime/device certification','elf_count':len(results),'64_bit_failures':failed,'32_bit_non_16k_informational':legacy,'entries':results},indent=2)+'\n')
print(json.dumps({'elf_count':len(results),'64_bit_failures':len(failed),'32_bit_informational':len(legacy),'examples':failed[:12]}))
