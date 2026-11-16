"""External acceptance of the exact production-signed APK using public Android UI.
No debug build, instrumentation injection, private app files or production key.
Run only on a disposable emulator; the script changes its network/display settings.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

PACKAGE='com.os73.dosos73'
OUT=Path('acceptance-evidence')


def adb(*args, binary=False):
    result=subprocess.run(['adb',*map(str,args)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
    return result.stdout if binary else result.stdout.decode('utf-8',errors='replace').strip()


def shell(*args): return adb('shell',*args)


def window():
    for _ in range(6):
        shell('rm','-f','/sdcard/acceptance-window.xml')
        dumped=shell('uiautomator','dump','/sdcard/acceptance-window.xml')
        # UIAutomator can exit 0 with a null root during Activity/window creation.
        # Never accept a stale dump from the previous window in that case.
        if 'ERROR' not in dumped:
            try:
                raw=shell('cat','/sdcard/acceptance-window.xml')
                tree=ET.fromstring(raw)
                (OUT/'latest-window.xml').write_text(raw,encoding='utf-8')
                return tree
            except (subprocess.CalledProcessError, ET.ParseError): pass
        time.sleep(0.5)
    raise AssertionError('Android did not expose an accessibility window after six fresh dumps')


def find(tree, label):
    return next((n for n in tree.iter('node') if label in (
        n.get('text'),n.get('content-desc'),n.get('resource-id')) and n.get('enabled')=='true'),None)


def wait(label, timeout=90):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        tree=window()
        node=find(tree,label)
        if node is not None: return node
        time.sleep(0.5)
    raise AssertionError(f'UI did not reach {label}; see latest-window.xml')


def click(label, scroll=False):
    for attempt in range(24 if scroll else 1):
        node=find(window(),label)
        if node is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            if x2>x1 and y2>y1:
                shell('input','tap',(x1+x2)//2,(y1+y2)//2)
                return
        if scroll:
            y1,y2=(1800,700) if attempt % 4 < 2 else (700,1800)
            shell('input','swipe',500,y1,500,y2,400)
    node=wait(label)
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    shell('input','tap',(x1+x2)//2,(y1+y2)//2)


def capture(name):
    (OUT/f'{name}.xml').write_text(ET.tostring(window(),encoding='unicode'),encoding='utf-8')
    (OUT/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))


def files():
    text=shell('find','/sdcard/Download/dosos73','-maxdepth','1','-type','f')
    return set(line for line in text.splitlines() if line.startswith('/sdcard/Download/dosos73/'))


def new_file(before, extension, timeout=180):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        paths=[p for p in files()-before if p.lower().endswith(extension)]
        if paths and 'DownloadService' not in shell('dumpsys','activity','services',PACKAGE):
            assert len(paths)==1
            return paths[0]
        time.sleep(2)
    raise AssertionError(f'No completed {extension} output')


def start(): shell('am','start','-W','-n',PACKAGE+'/.MainActivity')


def download(url, extension):
    before=files()
    shell('am','start','-W','-n',PACKAGE+'/.MainActivity','-a','android.intent.action.SEND',
          '-t','text/plain','--es','android.intent.extra.TEXT',url)
    wait('A new link was received')
    click('Review')
    click('download',scroll=True)
    path=new_file(before,extension)
    host=OUT/Path(path).name
    adb('pull',path,host)
    with urllib.request.urlopen(url,timeout=90) as response: reference=response.read()
    assert hashlib.sha256(host.read_bytes()).digest()==hashlib.sha256(reference).digest(), 'Downloaded body differs from origin'
    return path


def position():
    text=shell('dumpsys','media_session')
    (OUT/'media-session.txt').write_text(text,encoding='utf-8')
    # Restrict to this app's session before interpreting state/position.
    section=text[text.find('package='+PACKAGE):]
    match=re.search(r'PlaybackState.*?state=(?:[A-Z_]+\()?(\d+)\)?, position=(\d+)',section)
    if not match: raise AssertionError('Application media session unavailable')
    return tuple(map(int,match.groups()))


def main(apk, expected, page_size):
    OUT.mkdir(exist_ok=True)
    assert hashlib.sha256(apk.read_bytes()).hexdigest()==expected
    assert int(shell('getconf','PAGE_SIZE'))==page_size
    if page_size==16384:
        adb('root'); adb('wait-for-device')
        shell('setprop','bionic.linker.16kb.app_compat.enabled','false')
        shell('setprop','pm.16kb.app_compat.disabled','true')
        assert shell('getprop','bionic.linker.16kb.app_compat.enabled')=='false'
        assert shell('getprop','pm.16kb.app_compat.disabled')=='true'
    adb('install',apk)
    shell('mkdir','-p','/sdcard/Download/dosos73')
    shell('pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
    shell('wm','size','1080x2400');shell('wm','density','420')
    shell('input','keyevent','KEYCODE_WAKEUP');shell('wm','dismiss-keyguard')
    shell('cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales','en')
    adb('logcat','-c')
    start();wait('Download media');capture('home-en')
    print('Exact APK installed and launched',flush=True)
    # Public test media, fetched only by this explicitly invoked acceptance workflow.
    video=download('https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4','.mp4')
    print('MP4 download matches the origin SHA-256',flush=True)
    click('nav_files');click(Path(video).name);click('Play');wait('Pause')
    time.sleep(3)
    # MediaSession stores a position anchor, not a continuously sampled clock.
    # Pausing asks the real MediaPlayer to publish its actual currentPosition.
    shell('cmd','media_session','dispatch','pause');time.sleep(1)
    first=position()
    assert first[0]==2
    shell('cmd','media_session','dispatch','play')
    shell('svc','wifi','disable');shell('svc','data','disable')
    shell('input','keyevent','KEYCODE_HOME');time.sleep(4)
    assert position()[0]==3
    shell('cmd','media_session','dispatch','pause');time.sleep(1)
    second=position()
    assert second[0]==2 and second[1]>first[1]+2000
    start();wait('Play');paused=second[1]
    for _ in range(5):
        time.sleep(14)
        print('Waiting for idle playback resources to be released',flush=True)
    assert 'LocalPlaybackService' not in shell('dumpsys','activity','services',PACKAGE)
    click('Play');wait('Pause');time.sleep(2)
    assert position()[1]>=paused
    click('Stop');click('Close')
    print('Offline background playback and released-service resume passed',flush=True)
    # Convert the real downloaded video with all external network interfaces disabled.
    before=files()
    click('File actions');click('Convert video to MP3');click('offline_start',scroll=True)
    converted=new_file(before,'.mp3',timeout=240)
    host=OUT/'converted.mp3';adb('pull',converted,host)
    probe=subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=codec_name,codec_type','-of','json',str(host)],text=True)
    assert any(s['codec_name']=='mp3' and s['codec_type']=='audio' for s in json.loads(probe)['streams'])
    print('Offline video-to-MP3 conversion passed',flush=True)
    shell('svc','wifi','enable');shell('svc','data','enable');time.sleep(5)
    download('https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/AirReview-Landmarks-02-ChasingCorporate.mp3','.mp3')
    click('nav_home');capture('home-after-download')
    shell('settings','put','system','font_scale','2.0')
    shell('cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales','ar')
    start();capture('home-ar-large-font')
    shell('settings','put','system','accelerometer_rotation','0')
    shell('settings','put','system','user_rotation','1');time.sleep(2);capture('home-ar-landscape')
    shell('input','keyevent','KEYCODE_HOME');time.sleep(3)
    services=shell('dumpsys','activity','services',PACKAGE)
    assert 'LocalPlaybackService' not in services and 'DownloadService' not in services
    crashes=adb('logcat','-d','-b','crash')
    assert PACKAGE not in crashes
    report={'apk_sha256':expected,'page_size':page_size,'result':'PASS',
            'checks':['signed_apk_ui','mp4_exact_bytes','mp3_exact_bytes','offline_mp3_conversion',
                      'offline_background_playback','pause_releases_service','bookmark_resume',
                      'arabic_200percent_capture','rotation_capture','idle_services_absent'],
            'not_covered':['physical_ARM','TalkBack_user_session','live_YouTube','long_term_battery']}
    (OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apk',type=Path,required=True);p.add_argument('--sha256',required=True)
    p.add_argument('--page-size',type=int,required=True)
    a=p.parse_args()
    try: main(a.apk,a.sha256,a.page_size)
    finally:
        OUT.mkdir(exist_ok=True)
        (OUT/'last-screen.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
        (OUT/'crash.txt').write_text(adb('logcat','-d','-b','crash'),encoding='utf-8')
