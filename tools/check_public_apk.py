"""Verify a distributable APK contains only the public rule fixture and expected UI."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import zipfile


def check(apk: Path, language: str, aapt: Path) -> None:
    root = Path(__file__).resolve().parent.parent
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        assert not any(n.endswith('bypass_splash_rules.local.json') for n in names), 'Private rule bundle packaged'
        rules = z.read('assets/bypass_splash_rules.json')
        assert rules == (root / 'app/src/main/assets/bypass_splash_rules.json').read_bytes(), 'Public fixture changed'
        catalog = json.loads(z.read('assets/ui_en.json'))
        assert catalog['首页'] == 'Home' and catalog['广告黑匣子'] == 'Ad blackbox'
        assert len(catalog) >= 980
        assert not any(n.endswith(('.jks','.keystore','.db','.log')) for n in names)
    permissions = subprocess.check_output([str(aapt),'dump','permissions',str(apk)],text=True)
    assert 'android.permission.INTERNET' not in permissions
    assert 'android.permission.REQUEST_INSTALL_PACKAGES' not in permissions
    badging = subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True,encoding='utf-8')
    assert "name='app.bypassads'" in badging and "versionName='1.0.1'" in badging
    assert "versionCode='101'" in badging
    assert 'application-debuggable' not in badging
    manifest = subprocess.check_output([str(aapt),'dump','xmltree',str(apk),'AndroidManifest.xml'],text=True,encoding='utf-8')
    assert re.search(r'android:name[^\n]*"bypassUiLanguage"[^\n]*\n\s*A: android:value[^\n]*"'+language+'"',manifest), 'APK language metadata mismatch'
    assert re.search(r'android:allowBackup[^\n]*0x0\b',manifest), 'Automatic backup must be disabled'
    commit = subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    assert re.search(r'android:name[^\n]*"commitId"[^\n]*\n\s*A: android:value[^\n]*"'+commit+'"',manifest), 'APK source commit mismatch'
    print(f'PUBLIC APK PASS: {language}, {apk.stat().st_size} bytes, fixture SHA256 {hashlib.sha256(rules).hexdigest()}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--language',choices=('zh','en'),required=True)
    parser.add_argument('--aapt',type=Path,required=True)
    args = parser.parse_args()
    check(args.apk,args.language,args.aapt)
