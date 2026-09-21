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
# Module to the same-group dependencies it may declare.
modules = {'protocol': set(), 'sdk': {'protocol'}, 'sdk-extras': {'sdk'}, 'shizuku-compat': set()}
declared_licenses = {
    'protocol': {'Apache License 2.0'},
    'sdk': {'Apache License 2.0', 'MIT License'},
    'sdk-extras': {'Apache License 2.0', 'MIT License'},
    'shizuku-compat': {'MIT License'},
}
# Classes a module may ship outside eu/darken/porter/, spelled out one by one.
# shizuku-compat exists to supply the one class name the Shizuku server unparcels;
# anything else arriving in that artifact has to be added here on purpose.
allowed_classes = {
    'shizuku-compat': {
        'moe/shizuku/api/BinderContainer.class',
        'moe/shizuku/api/BinderContainer$Companion.class',
        'moe/shizuku/api/BinderContainer$Companion$CREATOR$1.class',
    },
}
components = ('provider', 'activity', 'service', 'receiver')
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for module, expected in modules.items():
    base = root / module / version / f'{module}-{version}'
    for suffix in ('.aar', '-sources.jar', '.pom', '.module'):
        assert Path(str(base) + suffix).is_file(), f'Missing {base}{suffix}'
    allowed = allowed_classes.get(module, set())
    with zipfile.ZipFile(str(base) + '.aar') as aar:
        jars = [name for name in aar.namelist() if name.endswith('.jar')]
        assert 'classes.jar' in jars, f'{module}: no classes.jar'
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
            assert f'META-INF/LICENSE-{module}' in classes.namelist(), f'{module}: missing license'
        # A foreign class would hide in a second jar, so read every one of them.
        for jar in jars:
            with zipfile.ZipFile(io.BytesIO(aar.read(jar))) as classes:
                for name in classes.namelist():
                    if not name.endswith('.class') or name in allowed:
                        continue
                    assert name.startswith('eu/darken/porter/'), f'{module}: foreign class {name} in {jar}'
                    assert not name.startswith(('moe/shizuku/', 'rikka/')), f'{module}: upstream class {name} in {jar}'
        if module == 'shizuku-compat':
            manifest = ET.fromstring(aar.read('AndroidManifest.xml'))
            for element in manifest.iter():
                assert element.tag not in components, f'{module}: manifest declares <{element.tag}>'
    pom = ET.parse(str(base) + '.pom').getroot()
    assert pom.findtext('m:groupId', namespaces=ns) == group
    assert pom.findtext('m:version', namespaces=ns) == version
    names = {entry.findtext('m:name', namespaces=ns)
             for entry in pom.findall('m:licenses/m:license', ns)}
    assert names == declared_licenses[module], (module, names, declared_licenses[module])
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
        # Gradle omits the key entirely when no capability is declared explicitly.
        for capability in variant.get('capabilities', []):
            assert capability['group'] != 'dev.rikka.shizuku', (module, capability)
        for dependency in variant.get('dependencies', []):
            assert dependency['group'] != 'dev.rikka.shizuku'
            if dependency['group'] == group:
                assert dependency['module'] in expected, (module, variant['name'], dependency['module'])
                assert dependency['version']['requires'] == version
    print(f'PASS {module}: complete artifacts, only allowed classes, no upstream capability')
