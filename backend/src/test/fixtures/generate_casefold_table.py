"""Unicode casefold data for exact legacy question-option comparison; no app/config imports."""
import json
import unicodedata
from pathlib import Path

root = Path(__file__).resolve().parents[4]
entries = {str(cp): chr(cp).casefold() for cp in range(0x110000)
           if not 0xD800 <= cp <= 0xDFFF and chr(cp).casefold() != chr(cp)}
target = root / 'backend/src/main/resources/unicode-casefold.json'
target.write_text(json.dumps({'unicodeVersion': unicodedata.unidata_version, 'entries': entries}, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
print('Generated Unicode casefold mappings:', len(entries))
