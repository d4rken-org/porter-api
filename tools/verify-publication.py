#!/usr/bin/env python3
"""Check the complete SDK publication and its transitive dependency coordinates."""
import io
import zipfile
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1]) / 'com/github/d4rken-org/porter-api'
version = sys.argv[2]
group = 'com.github.d4rken-org.porter-api'
modules = {'aidl': set(), 'shared': {'aidl'}, 'api': {'aidl', 'shared'},
           'provider': {'api'}, 'client': {'api', 'provider'}}
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for module, expected in modules.items():
    base = root / module / version / f'{module}-{version}'
    for suffix in ('.aar', '-sources.jar', '.pom', '.module'):
        assert Path(str(base) + suffix).is_file(), f'Missing {base}{suffix}'
    with zipfile.ZipFile(str(base) + '.aar') as aar:
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
            assert f'META-INF/LICENSE-{module}' in classes.namelist(), f'{module}: missing license'
    pom = ET.parse(str(base) + '.pom').getroot()
    assert pom.findtext('m:groupId', namespaces=ns) == group
    assert pom.findtext('m:version', namespaces=ns) == version
    found = set()
    for dependency in pom.findall('m:dependencies/m:dependency', ns):
        dependency_group = dependency.findtext('m:groupId', namespaces=ns)
        assert dependency_group != 'dev.rikka.shizuku', f'{module} still depends on upstream SDK'
        if dependency_group == group:
            found.add(dependency.findtext('m:artifactId', namespaces=ns))
            assert dependency.findtext('m:version', namespaces=ns) == version
    assert found == expected, (module, found, expected)
    metadata = json.loads(Path(str(base) + '.module').read_text())
    for variant in metadata['variants']:
        if variant['attributes'].get('org.gradle.category') != 'library':
            continue
        if module != 'client':
            capabilities = {(c['group'], c['name']) for c in variant['capabilities']}
            assert (group, module) in capabilities
            assert ('dev.rikka.shizuku', module) in capabilities
        for dependency in variant.get('dependencies', []):
            assert dependency['group'] != 'dev.rikka.shizuku'
            if dependency['group'] == group:
                assert dependency['version']['requires'] == version
    print(f'PASS {module}: complete artifacts, Porter dependencies and conflict metadata')
