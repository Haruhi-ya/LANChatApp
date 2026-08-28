# -*- coding: utf-8 -*-
"""重新绘制系统总体架构图（含好友功能），替换 docx 中的旧架构图"""
import sys
sys.stdout.reconfigure(encoding='utf-8')

import matplotlib
matplotlib.use('Agg')
from matplotlib import font_manager
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

font_manager.fontManager.addfont('C:/Windows/Fonts/msyh.ttc')
plt.rcParams['font.family'] = 'Microsoft YaHei'
plt.rcParams['axes.unicode_minus'] = False

NAVY   = '#1F4E79'
BLUE   = '#2E75B6'
SKY    = '#DEEBF7'
ORANGE = '#E8A33D'
LIGHT  = '#F2F6FB'
GRAY   = '#595F69'
DARK   = '#262B33'

fig, ax = plt.subplots(figsize=(13.3, 8.5), dpi=150)
ax.set_xlim(0, 100)
ax.set_ylim(0, 100)
ax.axis('off')

def box(x, y, w, h, text, fc, ec, fs=10.5, tc=DARK, bold=True, lw=1.2, sub=None):
    p = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.35,rounding_size=0.9",
                       fc=fc, ec=ec, lw=lw, mutation_aspect=1)
    ax.add_patch(p)
    if sub:
        ax.text(x + w/2, y + h*0.58, text, ha='center', va='center', fontsize=fs,
                color=tc, fontweight='bold' if bold else 'normal')
        ax.text(x + w/2, y + h*0.32, sub, ha='center', va='center', fontsize=fs-1.8,
                color=GRAY)
    else:
        ax.text(x + w/2, y + h/2, text, ha='center', va='center', fontsize=fs,
                color=tc, fontweight='bold' if bold else 'normal')

def arrow(x1, y1, x2, y2, color=NAVY, lw=1.6, style='-|>', text=None, ts=9,
          tx=0.5, ty=0.5, ls='-'):
    a = FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=style, mutation_scale=14,
                        color=color, lw=lw, linestyle=ls)
    ax.add_patch(a)
    if text:
        ax.text(x1 + (x2-x1)*tx, y1 + (y2-y1)*ty, text, fontsize=ts, color=GRAY,
                ha='center', va='center',
                bbox=dict(boxstyle='round,pad=0.18', fc='white', ec='none', alpha=0.9))

# ================= 客户端 =================
box(2, 55, 42, 40, '客 户 端', NAVY, NAVY, fs=13, tc='white')
box(4, 82, 38, 10.5, '', LIGHT, SKY, lw=1.0, sub='')
# 界面层三个模块
box(6, 83.5, 10.8, 7.5, '登录 / 注册', LIGHT, SKY, fs=9.3, sub='chatEntryUI')
box(17.6, 83.5, 10.8, 7.5, '主聊天窗口', LIGHT, SKY, fs=9.3, sub='公共聊天·好友·管理')
box(29.2, 83.5, 10.8, 7.5, '私聊 / 对话框', LIGHT, SKY, fs=9.3, sub='私聊·好友申请')
# 网络层
box(4, 68, 38, 8, '', LIGHT, ORANGE, lw=1.0)
box(6, 68.6, 34, 6.6, '网络层 chatClient', BLUE, ORANGE, fs=9.8, tc='white',
    sub=None)
ax.text(23, 63.6, '独立接收线程 · Listener 回调 · 状态缓存补发', ha='center', fontsize=8.4, color=GRAY)
# 界面层 → 网络层 箭头
arrow(23, 82.5, 23, 76.2, text='发送方法 / 回调通知', ts=8.2, tx=0.5, ty=0.62)
ax.text(2.5, 54.6, 'Swing 界面（事件分发线程 EDT）', fontsize=8, color=GRAY, ha='left')

# ================= TCP =================
arrow(44.6, 75, 53.4, 75, color=ORANGE, lw=2.2, style='<|-|>', ls='-',
      text='TCP Socket · 自定义文本行协议', ts=9.5, tx=0.5, ty=0.62)
ax.text(49, 85.5, 'UTF-8 · 冒号分隔 · 29 类命令', fontsize=8, color=GRAY, ha='center')
ax.text(49, 64.2, '内容字段固定放最后 · 消息 ID 服务端生成', fontsize=8, color=GRAY, ha='center')

# ================= 服务端 =================
box(56, 55, 42, 40, '服 务 端', BLUE, BLUE, fs=13, tc='white')
# 连接管理 + 协议分发
box(58, 83, 17.5, 9, '连接管理', LIGHT, SKY, fs=9.6, sub='主线程 accept · 一连接一线程')
box(78.5, 83, 17.5, 9, '协议分发', LIGHT, SKY, fs=9.6, sub='handleMessage · 29 类命令')
# 消息管道
box(58, 70, 38, 8.5, '消息管道（先入库后广播，保证一致）', LIGHT, ORANGE, fs=9.6,
    sub='清洗 → 图片魔数校验 → 入库 → 广播 → @提醒')
# 会话权限 + 好友
box(58, 58, 17.5, 8.5, '会话与权限', LIGHT, SKY, fs=9.6, sub='登录校验 · 踢人 · 封禁')
box(78.5, 58, 17.5, 8.5, '好友管理', LIGHT, SKY, fs=9.6, sub='申请 · 列表 · 删除')
ax.text(56.5, 54.6, 'Java Socket 多线程', fontsize=8, color=GRAY, ha='left')

# ================= JDBC =================
arrow(77, 54.2, 77, 47.5, color=BLUE, lw=2.0, text='JDBC（数据访问层 dbManager）', ts=9.5,
      tx=0.5, ty=0.35)

# ================= 数据库 =================
box(56, 8, 42, 38, 'MySQL 数据库（lanchat）', NAVY, NAVY, fs=13, tc='white')
tables = [
    ('Users', '用户 · 角色 · 头像'),
    ('PublicMessages', '公共消息 · 撤回ID'),
    ('PrivateMessages', '私聊双份行 · 未读'),
    ('Friendships', '好友关系'),
    ('FriendRequests', '好友申请'),
]
for i, (name, sub) in enumerate(tables):
    col = i % 3
    row = i // 3
    x = 58.5 + col * 13
    y = 25.5 - row * 14.5
    box(x, y, 12, 9.5, name, LIGHT, SKY, fs=9.2, sub=sub)
# 双份行注释
ax.text(76, 20.5, '双份行模型：A/B 各存一行，\n清空只影响自己一侧', fontsize=7.6, color=GRAY,
        ha='center', va='center')
ax.text(58, 8.8, '启动时自动建库建表 · 结构自动迁移（补列 · TEXT→MEDIUMTEXT）', fontsize=7.8,
        color=GRAY, ha='left')

plt.tight_layout(pad=0.3)
out = 'ppt_assets/架构图-新.png'
plt.savefig(out, dpi=150, bbox_inches='tight', facecolor='white')
print('新架构图已生成:', out)
