#!/usr/bin/env python3
"""Check that a minified consumer still has the user-service constructors the server calls.

Usage: verify-consumer-services.py <apk> <mapping.txt> <dexdump>
"""
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

apk, mapping, dexdump = sys.argv[1:4]
package = 'eu.darken.porter.sdk.consumer'
stub = f'{package}.IConsumerService$Stub'
# Service class to the constructor the server finds on it, as a dex method type.
expected = {
    f'{package}.ConsumerService': '()V',
    f'{package}.ConsumerContextService': '(Landroid/content/Context;)V',
}

renamed = {}
for line in Path(mapping).read_text().splitlines():
    match = re.fullmatch(r'(\S+) -> (\S+):', line)
    if match:
        renamed[match.group(1)] = match.group(2)


def descriptor(name):
    return 'L' + renamed.get(name, name).replace('.', '/') + ';'


# Descriptor to (superclass descriptor, {constructor type: access flags}).
classes = {}
with tempfile.TemporaryDirectory() as work, zipfile.ZipFile(apk) as zipped:
    for dex in (name for name in zipped.namelist() if re.fullmatch(r'classes\d*\.dex', name)):
        path = zipped.extract(dex, work)
        dump = subprocess.run([dexdump, path], check=True, capture_output=True, text=True).stdout
        current = None
        method = None
        for line in dump.splitlines():
            line = line.strip()
            if match := re.match(r"Class descriptor\s+: '(.+)'", line):
                current = match.group(1)
                classes[current] = [None, {}]
            elif match := re.match(r"Superclass\s+: '(.+)'", line):
                classes[current][0] = match.group(1)
            elif match := re.match(r"name\s+: '(.+)'", line):
                method = match.group(1)
            elif (match := re.match(r"type\s+: '(.+)'", line)) and method == '<init>':
                constructor = match.group(1)
            elif (match := re.match(r'access\s+: \S+ \((.*)\)', line)) and method == '<init>':
                classes[current][1][constructor] = match.group(1).split()
                method = None

for service, constructor in expected.items():
    found = classes.get(descriptor(service))
    assert found, f'{service} ({descriptor(service)}) is not in the APK'
    superclass, constructors = found
    assert superclass == descriptor(stub), f'{service} extends {superclass}, not {stub}'
    assert constructor in constructors, f'{service} has no {constructor} constructor: {sorted(constructors)}'
    assert 'PUBLIC' in constructors[constructor], f'{service}{constructor} is {constructors[constructor]}'
    print(f'{service} -> {descriptor(service)}: public {constructor} kept')
