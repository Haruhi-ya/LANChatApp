# -*- coding: utf-8 -*-
"""确认样式 ID 与样式名映射、正文 pStyle 引用分布"""
import sys, re
sys.stdout.reconfigure(encoding='utf-8')

import docx

doc = docx.Document('详细设计及测试报告_赵翔_25112002136.docx')

# 1. styles.xml 中所有样式：ID -> name -> 颜色
styles = doc.styles
name_of = {}
for s in styles:
    name_of[s.style_id] = s.name

print("=== 正文段落引用的 pStyle 分布 ===")
from collections import Counter
refs = Counter()
body = doc.element.body
ns = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
for child in body:
    if child.tag.endswith('}p'):
        ps = child.find(f'{ns}pPr/{ns}pStyle')
        if ps is not None:
            refs[ps.get(f'{ns}val')] += 1
for sid, cnt in sorted(refs.items()):
    print(f"styleId={sid} name={name_of.get(sid, '?')} 引用次数={cnt}")

# 2. 标题相关样式颜色详情
print("\n=== 标题样式颜色详情 ===")
for sid in sorted(refs):
    s = doc.styles[sid]
    rpr = s.element.find(f'{ns}rPr')
    if rpr is not None:
        color = rpr.find(f'{ns}color')
        print(f"styleId={sid} name={s.name} color={color.get(f'{ns}val') if color is not None else None}")

# 3. 检查样式 3 的完整定义（是否有加粗/字体等）
s3 = doc.styles['3']
print("\n=== styleId=3 完整 XML ===")
from lxml import etree
print(etree.tostring(s3.element, pretty_print=True).decode()[:1200])

# 4. 检查封面表（表0）后的段落及封面表格边框样式（判断是否也改）
t0 = doc.tables[0]
print("\n=== 封面表0 样式:", t0.style.name if t0.style else None)
