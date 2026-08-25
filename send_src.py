#!/usr/bin/env python3
"""发送全部源码到邮箱（只含版本管理文件，不含任何构建产物）。

用法:
    python send_src.py

归档方式: git archive HEAD —— 天然只包含 git 跟踪的源码文件,
自动排除 target/、node_modules/、logs/ 等一切未跟踪内容。
"""
import os
import subprocess
import sys
import tempfile
from datetime import date
from email import encoders
from email.mime.base import MIMEBase
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
import smtplib

SMTP_SERVER = "smtp.qq.com"
SMTP_PORT = 465
FROM = "xiang527541@qq.com"
PASSWORD = "elahfydqaahmbgac"
TO = "lihua@optel.com.cn"

TODAY = date.today().strftime("%Y-%m-%d")


def run_git(*args):
    r = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        sys.exit(f"git {' '.join(args)} 失败: {r.stderr}")
    return r.stdout


def main():
    archive_name = f"qx-inspection-tool-src-{date.today().strftime('%Y%m%d')}.zip"
    archive_path = os.path.join(tempfile.gettempdir(), archive_name)

    print("归档 HEAD 版本管理文件 ...")
    subprocess.run(["git", "archive", "--format=zip", "-o", archive_path, "HEAD"], check=True)

    size = os.path.getsize(archive_path)
    n_files = subprocess.run(
        ["git", "ls-files"], capture_output=True, text=True, encoding="utf-8"
    ).stdout.count("\n")
    print(f"归档完成: {archive_path} ({size / 1024 / 1024:.1f} MB, {n_files} 个文件)")
    if size / 1024 / 1024 * 4 / 3 > 50:
        sys.exit("警告: base64 后超过 QQ 邮箱 50MB 附件上限, 取消发送")

    head = run_git("log", "--oneline", "-1")

    msg = MIMEMultipart()
    msg["From"] = FROM
    msg["To"] = TO
    msg["Subject"] = f"QX巡检工具 全部源码（版本管理文件）({TODAY})"
    body = f"""QX设备直连巡检工具 完整项目源码，仅包含 git 版本管理的文件，不含任何构建产物。

当前提交: {head.strip()}
文件数: {n_files}
归档大小: {size / 1024 / 1024:.1f} MB

功能模块:
- M1: 设备发现 + 统一配置 + 批量连接/断开 + 断线重连
- M2: 设备类型统计（库存/在线口径）
- M3: 光功率巡检（0x2410采集 + 门限判定 + 查询导出）
- M4: 定时巡检调度 + 进度展示 + 摘要
- M5: 历史趋势 + 越限异常汇总
- M6: 库存统计（网元/盘/端口）
"""
    msg.attach(MIMEText(body, "plain", "utf-8"))

    with open(archive_path, "rb") as f:
        part = MIMEBase("application", "zip")
        part.set_payload(f.read())
        encoders.encode_base64(part)
        part.add_header("Content-Disposition", "attachment", filename=archive_name)
        msg.attach(part)

    print("发送中 ...")
    server = smtplib.SMTP_SSL(SMTP_SERVER, SMTP_PORT)
    server.login(FROM, PASSWORD)
    server.sendmail(FROM, [TO], msg.as_string())
    server.quit()
    print("已发送到", TO)


if __name__ == "__main__":
    main()
