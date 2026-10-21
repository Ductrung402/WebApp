"""One explicit Gemini call using private local settings; never prints key or DB config."""
import os
import subprocess
from pathlib import Path
from dotenv import dotenv_values

root = Path(__file__).resolve().parents[4]
values = dotenv_values(root / 'backend/.env')
environment = os.environ.copy()
for name in ('GEMINI_API_KEY', 'GOOGLE_API_KEY', 'GEMINI_MODEL'):
    if not environment.get(name) and values.get(name):
        environment[name] = values[name]
java = Path(environment['JAVA_HOME']) / 'bin/java.exe'
classpath = os.pathsep.join(('target/test-classes', 'target/classes', 'target/ROOT/WEB-INF/lib/*'))
result = subprocess.run([str(java), '-cp', classpath, 'com.ezam.probe.GeminiLiveProbe'], cwd=root/'backend', env=environment, timeout=25)
raise SystemExit(result.returncode)
