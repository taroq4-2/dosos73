"""Produce an artifact-bound CycloneDX inventory without modifying application contents."""
import argparse,hashlib,io,json,uuid,zipfile,datetime,xml.etree.ElementTree as E
from pathlib import Path
from urllib.parse import quote
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=Path,required=True);p.add_argument('--maven',type=Path,required=True)
p.add_argument('--output',type=Path,required=True);p.add_argument('--version',required=True)
a=p.parse_args()
def digest(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
sha=digest(a.apk); components=[]; seen_refs={}
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
for row in json.loads(a.maven.read_text(encoding='utf-8')):
 ref='pkg:maven/'+quote(row['group'],safe='')+'/'+quote(row['name'],safe='')+'@'+quote(row['version'],safe='')+'?type='+row['type']
 if ref in seen_refs:
  if seen_refs[ref] == row['sha256']: continue
  ref += '#artifact-' + row['sha256'][:12]
 seen_refs[ref]=row['sha256']
 item={'type':'library','bom-ref':ref,'group':row['group'],'name':row['name'],'version':row['version'],'purl':ref,'hashes':[{'alg':'SHA-256','content':row['sha256']}], 'properties':[{'name':'dosos73:artifact-file','value':row['file']}]}
 licenses=[]
 for pom in (cache/row['group']/row['name']/row['version']).glob('*/*.pom'):
  try:
   root=E.parse(pom).getroot(); ns={'m':'http://maven.apache.org/POM/4.0.0'}
   for node in root.findall('./m:licenses/m:license',ns):
    name=node.findtext('m:name',namespaces=ns);url=node.findtext('m:url',namespaces=ns)
    if name:
     value={'name':name}
     if url and url.startswith(('http://','https://')):value['url']=url
     licenses.append({'license':value})
  except E.ParseError:pass
 if licenses:item['licenses']=licenses
 else:item['properties'].append({'name':'dosos73:license-review','value':'Not established from a directly cached POM; inherited/native licenses require further review.'})
 components.append(item)
native_count=0
with zipfile.ZipFile(a.apk) as apk:
 def inspect(z,prefix=''):
  global native_count
  for entry in z.infolist():
   if entry.is_dir():continue
   data=z.read(entry);name=prefix+entry.filename
   if data.startswith(b'\x7fELF'):
    native_count+=1
    components.append({'type':'file','bom-ref':'file:'+name,'name':name,'hashes':[{'alg':'SHA-256','content':hashlib.sha256(data).hexdigest()}], 'properties':[{'name':'dosos73:uncompressed-bytes','value':str(len(data))},{'name':'dosos73:license-review','value':'Binary inventory only; source and component correspondence are separate evidence.'}]})
   elif entry.filename.endswith('.zip.so'):
    with zipfile.ZipFile(io.BytesIO(data)) as nested:inspect(nested,name+'!/')
 inspect(apk)
components.extend([
 {'type':'library','bom-ref':'ffmpeg:9.0.2','name':'FFmpeg','version':'9.0.2','licenses':[{'expression':'LGPL-2.1-or-later'}],'externalReferences':[{'type':'vcs','url':'https://github.com/FFmpeg/FFmpeg/tree/946fcce07b6dcd0331c8cc609192aeff5e1924f8'}], 'properties':[{'name':'dosos73:license-evidence','value':'Pinned build recipe and all four config.h/config.mak files; CONFIG_GPL=0, CONFIG_NONFREE=0. Not a complete legal certification.'}]},
 {'type':'library','bom-ref':'lame:3.100','name':'LAME','version':'3.100','licenses':[{'expression':'LGPL-2.0-or-later'}],'hashes':[{'alg':'SHA-256','content':'ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e'}],'properties':[{'name':'dosos73:hash-scope','value':'Official source archive, not a compiled binary hash.'}]},
 {'type':'library','bom-ref':'yt-dlp:2026.10.21','name':'yt-dlp application-pinned zipapp','version':'2026.10.21','hashes':[{'alg':'SHA-256','content':'b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14'}],'properties':[{'name':'dosos73:license-evidence','value':'Original Unlicense plus bundled ISC/MIT notices; see docs/licenses/yt-dlp-2026.10.21.txt.'}]}
])
bom={'bomFormat':'CycloneDX','specVersion':'1.6','serialNumber':'urn:uuid:'+str(uuid.uuid5(uuid.NAMESPACE_URL,sha)),'version':1,'metadata':{'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),'component':{'type':'application','bom-ref':'dosos73:'+a.version,'name':'com.os73.dosos73','version':a.version,'hashes':[{'alg':'SHA-256','content':sha}],'licenses':[{'expression':'GPL-3.0-or-later'}]},'properties':[{'name':'dosos73:scope','value':'Resolved Maven artifacts plus actual nested ELF inventory and known pinned engines. Flat inventory; no complete dependency-edge, native license or vulnerability certification.'}]},'components':components}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(bom,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'maven_components':len(json.loads(a.maven.read_text())),'elf_files':native_count,'total_components':len(components),'apk_sha256':sha}))
