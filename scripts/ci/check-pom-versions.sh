#!/usr/bin/env bash
# 检查依赖版本只在 meridian-bom 声明（编码规范「依赖管理」、架构设计「防漂移机制」）。
#
# 允许写 <version> 的位置只有两处：meridian-bom/pom.xml，以及根 pom.xml 的
# <dependencyManagement> 与 <build><pluginManagement>。其余 POM 中任何 <dependency>
# （含 profile 与插件依赖）都不能写 <version>。发现违规时逐条列出并以非 0 退出。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

find . -name pom.xml -not -path '*/target/*' -not -path '*/node_modules/*' -print0 | sort -z |
  python3 -I -c '
import sys
import xml.etree.ElementTree as ET

NS = "{http://maven.apache.org/POM/4.0.0}"

def tag(element):
    return element.tag.replace(NS, "")

def child(element, name):
    return element.find(NS + name)

violations = []
for path in sys.stdin.buffer.read().decode().split("\0"):
    if not path or path == "./meridian-bom/pom.xml":
        continue
    project = ET.parse(path).getroot()
    if path == "./pom.xml":
        # 根 POM 的 dependencyManagement 与 pluginManagement 是允许声明版本的位置
        for parent, name in ((project, "dependencyManagement"), (child(project, "build"), "pluginManagement")):
            if parent is not None and child(parent, name) is not None:
                parent.remove(child(parent, name))
    for dependency in project.iter(NS + "dependency"):
        if child(dependency, "version") is not None:
            group = child(dependency, "groupId")
            artifact = child(dependency, "artifactId")
            coordinates = "%s:%s" % (
                group.text if group is not None else "?",
                artifact.text if artifact is not None else "?",
            )
            violations.append("%s: %s 写了 <version>" % (path[2:], coordinates))

if violations:
    print("依赖版本只能在 meridian-bom 或根 POM 的 dependencyManagement、pluginManagement 中声明：")
    for line in violations:
        print("  - " + line)
    sys.exit(1)
print("check-pom-versions: OK")
'
