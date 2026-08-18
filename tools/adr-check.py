# -*- coding: utf-8 -*-
"""Проверка архитектурных инвариантов ADR по коду."""
import glob
import os
import re

ROOTS = ['core/**/src/main/**/*.kt', 'data/src/main/**/*.kt',
         'executors/src/main/**/*.kt', 'app/src/main/**/*.kt']


def files():
    out = []
    for r in ROOTS:
        out += glob.glob(r, recursive=True)
    return [f for f in out if os.sep + 'build' + os.sep not in f]


def object_body(s, m):
    start = m.end()
    depth, i = 1, start
    while i < len(s) and depth > 0:
        if s[i] == '{':
            depth += 1
        elif s[i] == '}':
            depth -= 1
        i += 1
    return s[start:i]


bad = []
for f in files():
    s = open(f, encoding='utf-8').read()
    for m in re.finditer(r'^object (\w+) \{', s, re.M):
        body = object_body(s, m)
        pat = r'^    (?:private |internal )?(var \w+|val \w+\s*[:=]\s*(?:mutableListOf|mutableMapOf|ConcurrentHashMap|AtomicReference|AtomicInteger))'
        for pm in re.finditer(pat, body, re.M):
            bad.append((f.replace(os.sep, '/'), m.group(1), pm.group(1)[:40]))

print('ADR-010 — синглтонов с изменяемым состоянием:', len(bad))
for b in bad[:10]:
    print('   ', b[0], '|', b[1], '|', b[2])

# ADR-011: сеть и файлы прямо в core
net = []
for f in files():
    if '/core/' not in f.replace(os.sep, '/'):
        continue
    s = open(f, encoding='utf-8').read()
    if re.search(r'HttpURLConnection|java\.io\.File\(', s):
        net.append(f.replace(os.sep, '/'))
print()
print('ADR-011 — файлов core с прямой сетью/файлами:', len(net))
for n in net:
    print('   ', n)
