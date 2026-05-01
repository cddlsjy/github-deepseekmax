#!/usr/bin/env python3
"""
GitHub 项目小助手 v6.1
- 设置集中管理：Token、字体缩放、自动解压、上传位置、自定义编译指令
- 工作目录管理，下载解压均在工作目录下，记住当前项目子目录
- 两个上传按钮：快速上传（当前项目）、手动上传（完全自定义）
- 手动上传支持指定仓库内目标路径（如 .github/workflows/）
- 修复 Token 初始化问题
"""

import os, sys, json, base64, tempfile, shutil, subprocess, zipfile, threading, time
from tkinter import *
from tkinter import ttk, filedialog, messagebox, font as tkfont

import requests

CONFIG_FILE = os.path.expanduser("~/.github_uploader_simple.json")

def load_config():
    try:
        with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
            return json.load(f)
    except:
        return {}

def save_config(cfg):
    try:
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
            json.dump(cfg, f, indent=2)
    except:
        pass

def log(msg):
    if hasattr(log, 'widget'):
        log.widget.insert(END, msg + "\n")
        log.widget.see(END)

def set_log_widget(widget):
    log.widget = widget

def run_in_thread(func):
    def wrapper(*args, **kwargs):
        threading.Thread(target=func, args=args, kwargs=kwargs, daemon=True).start()
    return wrapper

class App:
    def __init__(self, root):
        self.root = root
        self.root.title("GitHub 项目小助手 v6.1")
        self.root.geometry("750x650")
        self.root.minsize(650, 500)

        self.config = load_config()
        self.token = self.config.get("token", "")
        self.repo_full = self.config.get("repo", "")
        self.branch = self.config.get("branch", "main")
        self.work_dir = self.config.get("work_dir", "")
        self.trae_path = self.config.get("trae_path", "")

        self.auto_extract = self.config.get("auto_extract", False)
        self.delete_zip_after_extract = self.config.get("delete_zip_after_extract", False)
        self.upload_target = self.config.get("upload_target", "release")
        self.font_scale = self.config.get("font_scale", 1.0)
        self.custom_build_cmd = self.config.get("custom_build_cmd", "cd {project_dir} && gradle assembleDebug")

        self.repos_cache = []
        self.branches_cache = []
        self.last_extracted_dir = None

        self.apply_font_scale(self.font_scale)
        self.setup_ui()
        if self.token:
            self._init_api_login()

    def apply_font_scale(self, scale=None):
        if scale is None:
            scale = self.font_scale
        base_size = int(9 * scale)
        default_font = tkfont.nametofont("TkDefaultFont")
        default_font.configure(size=base_size)
        text_font = tkfont.nametofont("TkTextFont")
        text_font.configure(size=base_size)
        fixed_font = tkfont.nametofont("TkFixedFont")
        fixed_font.configure(size=base_size)
        self.root.option_add("*Font", default_font)
        style = ttk.Style()
        style.configure(".", font=default_font)
        style.configure("Treeview", font=default_font, rowheight=int(base_size*2.2))
        style.configure("Treeview.Heading", font=(default_font.actual()["family"], base_size, "bold"))

    def setup_ui(self):
        main = ttk.Frame(self.root, padding="10")
        main.pack(fill=BOTH, expand=True)

        r = 0

        # 仓库 + 分支
        ttk.Label(main, text="目标仓库", font=("", 10, "bold")).grid(row=r, column=0, sticky=W, pady=(10,2))
        ttk.Label(main, text="分支", font=("", 10, "bold")).grid(row=r, column=1, sticky=W, padx=(10,0)); r+=1
        repo_frame = ttk.Frame(main)
        repo_frame.grid(row=r, column=0, columnspan=3, sticky=EW, pady=(0,5)); r+=1
        self.repo_combo = ttk.Combobox(repo_frame, values=[], postcommand=self.load_repos)
        self.repo_combo.pack(side=LEFT, fill=X, expand=True)
        self.repo_combo.bind('<<ComboboxSelected>>', self.on_repo_selected)
        self.branch_combo = ttk.Combobox(repo_frame, values=[], postcommand=self.load_branches, width=15)
        self.branch_combo.pack(side=LEFT, padx=(10,0))
        self.branch_combo.bind('<<ComboboxSelected>>', self.on_branch_selected)
        ttk.Button(repo_frame, text="刷新", command=self.load_repos).pack(side=LEFT, padx=(5,0))

        # 工作目录
        ttk.Label(main, text="工作目录", font=("", 10, "bold")).grid(row=r, column=0, sticky=W, pady=(10,2)); r+=1
        work_frame = ttk.Frame(main)
        work_frame.grid(row=r, column=0, columnspan=3, sticky=EW, pady=(0,5)); r+=1
        self.work_var = StringVar(value=self.work_dir)
        ttk.Entry(work_frame, textvariable=self.work_var, width=50, state='readonly').pack(side=LEFT, fill=X, expand=True)
        ttk.Button(work_frame, text="浏览...", command=self.browse_work).pack(side=LEFT, padx=5)
        ttk.Label(work_frame, text="项目子目录:").pack(side=LEFT, padx=(10,0))
        self.project_var = StringVar(value=self.last_extracted_dir if self.last_extracted_dir else "（无）")
        ttk.Label(work_frame, textvariable=self.project_var, foreground="blue").pack(side=LEFT)

        # Trae 路径
        ttk.Label(main, text="Trae 可执行文件路径", font=("", 10, "bold")).grid(row=r, column=0, sticky=W, pady=(10,2)); r+=1
        trae_frame = ttk.Frame(main)
        trae_frame.grid(row=r, column=0, columnspan=3, sticky=EW, pady=(0,5)); r+=1
        self.trae_var = StringVar(value=self.trae_path)
        ttk.Entry(trae_frame, textvariable=self.trae_var, width=50, state='readonly').pack(side=LEFT, fill=X, expand=True)
        ttk.Button(trae_frame, text="浏览...", command=self.browse_trae).pack(side=LEFT, padx=5)

        # 功能按钮
        btn_frame = ttk.Frame(main)
        btn_frame.grid(row=r, column=0, columnspan=3, pady=15); r+=1
        ttk.Button(btn_frame, text="1. 下载 ZIP", command=self.download_zip).pack(side=LEFT, padx=5)
        ttk.Button(btn_frame, text="2. 解压 ZIP", command=self.extract_zip).pack(side=LEFT, padx=5)
        ttk.Button(btn_frame, text="3. 打开目录", command=self.open_project_dir).pack(side=LEFT, padx=5)
        ttk.Button(btn_frame, text="4. 编译指令", command=self.custom_build_command).pack(side=LEFT, padx=5)
        ttk.Button(btn_frame, text="5. 快速上传", command=self.quick_upload).pack(side=LEFT, padx=5)
        ttk.Button(btn_frame, text="6. 手动上传", command=self.show_manual_upload_dialog).pack(side=LEFT, padx=5)

        # 日志
        ttk.Label(main, text="操作日志", font=("", 10, "bold")).grid(row=r, column=0, sticky=W); r+=1
        log_frame = ttk.Frame(main)
        log_frame.grid(row=r, column=0, columnspan=3, sticky=NSEW); r+=1
        self.log_text = Text(log_frame, height=10, wrap=WORD, font=("Consolas", int(9*self.font_scale)))
        scroll = ttk.Scrollbar(log_frame, command=self.log_text.yview)
        self.log_text.config(yscrollcommand=scroll.set)
        scroll.pack(side=RIGHT, fill=Y)
        self.log_text.pack(fill=BOTH, expand=True)
        set_log_widget(self.log_text)

        main.rowconfigure(r-1, weight=1)
        main.columnconfigure(0, weight=1)

        # 菜单
        menubar = Menu(self.root)
        self.root.config(menu=menubar)
        settings_menu = Menu(menubar, tearoff=0)
        menubar.add_cascade(label="设置", menu=settings_menu)
        settings_menu.add_command(label="选项...", command=self.open_settings)

        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    # ---------- 内部 API 登录 ----------
    def _init_api_login(self):
        if not self.token:
            return
        try:
            resp = requests.get("https://api.github.com/user",
                               headers={"Authorization": f"token {self.token}"})
            resp.raise_for_status()
            user = resp.json()['login']
            log(f"✅ 已登录: {user}")
            self.load_repos()
        except Exception as e:
            log(f"❌ Token 验证失败: {e}")
            self.token = ""
            self.config["token"] = ""
            save_config(self.config)

    def browse_work(self):
        d = filedialog.askdirectory()
        if d:
            self.work_var.set(d)
            self.work_dir = d
            self.config["work_dir"] = d
            save_config(self.config)
            log(f"📁 工作目录: {d}")

    def browse_trae(self):
        path = filedialog.askopenfilename(title="选择 Trae 可执行文件")
        if path:
            self.trae_var.set(path)
            self.trae_path = path
            self.config["trae_path"] = path
            save_config(self.config)

    def apply_token(self, silent=False):
        token = self.token_var.get().strip()
        if not token:
            if not silent:
                messagebox.showerror("错误", "Token 不能为空")
            return
        self.token = token
        self.config["token"] = token
        save_config(self.config)
        self._init_api_login()

    @run_in_thread
    def load_repos(self):
        if not self.token: return
        log("🔄 加载仓库列表...")
        try:
            headers = {"Authorization": f"token {self.token}"}
            resp = requests.get("https://api.github.com/user/repos?per_page=100", headers=headers)
            resp.raise_for_status()
            names = [r["full_name"] for r in resp.json()]
            self.root.after(0, lambda: self._update_repo_combo(names))
        except Exception as e:
            log(f"❌ 加载仓库失败: {e}")

    def _update_repo_combo(self, names):
        self.repos_cache = names
        self.repo_combo['values'] = names
        if self.repo_full in names:
            self.repo_combo.set(self.repo_full)
            self.load_branches()
        elif names:
            self.repo_combo.set(names[0])
            self.on_repo_selected()

    def on_repo_selected(self, event=None):
        self.repo_full = self.repo_combo.get()
        self.config["repo"] = self.repo_full
        save_config(self.config)
        log(f"已选择仓库: {self.repo_full}")
        self.load_branches()

    @run_in_thread
    def load_branches(self):
        repo = self.repo_combo.get()
        if not repo or "/" not in repo: return
        log("🔄 加载分支列表...")
        try:
            headers = {"Authorization": f"token {self.token}"}
            resp = requests.get(f"https://api.github.com/repos/{repo}/branches", headers=headers)
            resp.raise_for_status()
            branches = [b["name"] for b in resp.json()]
            self.root.after(0, lambda: self._update_branch_combo(branches))
        except Exception as e:
            log(f"❌ 加载分支失败: {e}")

    def _update_branch_combo(self, branches):
        self.branches_cache = branches
        self.branch_combo['values'] = branches
        if self.branch in branches:
            self.branch_combo.set(self.branch)
        elif branches:
            default = "main" if "main" in branches else ("master" if "master" in branches else branches[0])
            self.branch_combo.set(default)
            self.branch = default
            self.config["branch"] = self.branch
            save_config(self.config)

    def on_branch_selected(self, event=None):
        self.branch = self.branch_combo.get()
        self.config["branch"] = self.branch
        save_config(self.config)

    # ===================== 设置对话框（移除自动上传） =====================
    def open_settings(self):
        dialog = Toplevel(self.root)
        dialog.title("设置")
        dialog.geometry("550x580")
        dialog.transient(self.root)
        dialog.grab_set()

        nb = ttk.Notebook(dialog)
        nb.pack(fill=BOTH, expand=True, padx=5, pady=5)

        basic_frame = ttk.Frame(nb, padding="10")
        nb.add(basic_frame, text="基本设置")

        row = 0
        # Token
        ttk.Label(basic_frame, text="GitHub Token:").grid(row=row, column=0, sticky=W, pady=5)
        self.token_var = StringVar(value=self.token)
        tok_entry = ttk.Entry(basic_frame, textvariable=self.token_var, width=50, show="*")
        tok_entry.grid(row=row, column=1, sticky=EW, padx=5)
        show_token_var = BooleanVar(value=False)
        ttk.Checkbutton(basic_frame, text="显示", variable=show_token_var,
                        command=lambda: tok_entry.config(show="" if show_token_var.get() else "*")).grid(row=row, column=2)
        row += 1

        # 字体缩放
        ttk.Label(basic_frame, text="字体缩放:").grid(row=row, column=0, sticky=W, pady=5)
        font_scale_var = DoubleVar(value=self.font_scale)
        ttk.Spinbox(basic_frame, from_=0.8, to=2.0, increment=0.1, textvariable=font_scale_var, width=5).grid(row=row, column=1, sticky=W)
        row += 1

        # 下载选项
        ttk.Label(basic_frame, text="下载选项", font=("", 10, "bold")).grid(row=row, column=0, columnspan=3, sticky=W, pady=(15,5)); row+=1
        auto_extract_var = BooleanVar(value=self.auto_extract)
        ttk.Checkbutton(basic_frame, text="下载 ZIP 后自动解压（在工作目录下创建项目子目录）",
                        variable=auto_extract_var).grid(row=row, column=0, columnspan=3, sticky=W); row+=1
        delete_zip_var = BooleanVar(value=self.delete_zip_after_extract)
        ttk.Checkbutton(basic_frame, text="自动解压后删除压缩包",
                        variable=delete_zip_var).grid(row=row, column=0, columnspan=3, sticky=W); row+=1

        # 上传选项（仅位置）
        ttk.Label(basic_frame, text="上传选项", font=("", 10, "bold")).grid(row=row, column=0, columnspan=3, sticky=W, pady=(15,5)); row+=1
        upload_target_var = StringVar(value=self.upload_target)
        ttk.Label(basic_frame, text="常规上传位置:").grid(row=row, column=0, sticky=W, padx=(20,0), pady=5)
        ttk.Radiobutton(basic_frame, text="仓库根目录", variable=upload_target_var, value="repo_root").grid(row=row, column=1, sticky=W); row+=1
        ttk.Radiobutton(basic_frame, text="Release 资产（推荐）", variable=upload_target_var, value="release").grid(row=row, column=1, sticky=W); row+=1

        # 自定义编译指令
        ttk.Label(basic_frame, text="编译指令模板", font=("", 10, "bold")).grid(row=row, column=0, columnspan=3, sticky=W, pady=(15,5)); row+=1
        custom_cmd_var = StringVar(value=self.custom_build_cmd)
        ttk.Entry(basic_frame, textvariable=custom_cmd_var, width=60).grid(row=row, column=0, columnspan=3, sticky=EW, padx=5); row+=1
        ttk.Label(basic_frame, text="可用占位符: {work_dir} {project_dir}", foreground="gray").grid(row=row, column=0, columnspan=3, sticky=W); row+=1

        basic_frame.columnconfigure(1, weight=1)

        btn_frame = ttk.Frame(dialog)
        btn_frame.pack(pady=10)
        ttk.Button(btn_frame, text="保存", command=lambda: self.save_settings(
            font_scale_var, auto_extract_var, delete_zip_var, upload_target_var, custom_cmd_var, dialog
        )).pack(side=LEFT, padx=5)
        ttk.Button(btn_frame, text="取消", command=dialog.destroy).pack(side=LEFT, padx=5)

    def save_settings(self, font_scale_var, auto_extract_var, delete_zip_var, upload_target_var, custom_cmd_var, dialog):
        new_token = self.token_var.get().strip()
        new_scale = font_scale_var.get()
        self.auto_extract = auto_extract_var.get()
        self.delete_zip_after_extract = delete_zip_var.get()
        self.upload_target = upload_target_var.get()
        self.custom_build_cmd = custom_cmd_var.get()

        self.config.update({
            "token": new_token,
            "font_scale": new_scale,
            "auto_extract": self.auto_extract,
            "delete_zip_after_extract": self.delete_zip_after_extract,
            "upload_target": self.upload_target,
            "custom_build_cmd": self.custom_build_cmd
        })
        save_config(self.config)

        if new_scale != self.font_scale:
            self.font_scale = new_scale
            self.apply_font_scale(new_scale)
            self.log_text.configure(font=("Consolas", int(9*new_scale)))

        if new_token != self.token:
            self.token = new_token
            if new_token:
                self._init_api_login()
            else:
                log("Token 已清空")

        log("设置已保存")
        dialog.destroy()

    # ===================== 下载 ZIP（不再自动上传） =====================
    @run_in_thread
    def download_zip(self):
        repo = self.repo_combo.get()
        branch = self.branch_combo.get()
        if not repo or "/" not in repo:
            log("❌ 请先选择仓库")
            return
        owner, name = repo.split("/", 1)

        if not self.work_dir or not os.path.isdir(self.work_dir):
            log("❌ 请先设置工作目录")
            return
        zip_path = os.path.join(self.work_dir, f"{name}-{branch}.zip")
        log(f"📥 下载 {repo}@{branch} 到 {zip_path} ...")
        try:
            url = f"https://api.github.com/repos/{owner}/{name}/zipball/{branch}"
            headers = {"Authorization": f"token {self.token}"} if self.token else {}
            resp = requests.get(url, headers=headers, allow_redirects=True)
            resp.raise_for_status()
            with open(zip_path, 'wb') as f:
                f.write(resp.content)
            log(f"✅ 下载完成: {zip_path}")

            if self.auto_extract:
                log("📂 开始自动解压...")
                self._extract_zip_file(zip_path, self.work_dir, remove_top=True, subdir_name=name)
                if self.delete_zip_after_extract:
                    try:
                        os.remove(zip_path)
                        log("🗑 已删除压缩包")
                    except:
                        pass
                # 不再自动上传，仅更新项目目录以便用户后续一键上传
        except Exception as e:
            log(f"❌ 下载失败: {e}")

    # ---------- 手动解压 ----------
    @run_in_thread
    def extract_zip(self):
        zip_path = filedialog.askopenfilename(title="选择 ZIP 压缩包", filetypes=[("ZIP 文件", "*.zip")])
        if not zip_path: return
        if not self.work_dir:
            log("❌ 请先设置工作目录")
            return

        repo_name = ""
        if self.repo_full and "/" in self.repo_full:
            repo_name = self.repo_full.split("/", 1)[1]
        else:
            repo_name = os.path.splitext(os.path.basename(zip_path))[0]

        if not messagebox.askyesno("确认", f"将解压到工作目录下的 {repo_name}/ 子目录\n确定吗？"):
            return
        self._extract_zip_file(zip_path, self.work_dir, remove_top=True, subdir_name=repo_name)

    def _extract_zip_file(self, zip_path, dest_dir, remove_top=True, subdir_name=None):
        try:
            with zipfile.ZipFile(zip_path, 'r') as zf:
                names = zf.namelist()
                top_dirs = set()
                for n in names:
                    parts = n.split('/')
                    if parts[0] and not parts[0].startswith('__'):
                        top_dirs.add(parts[0])

                prefix = ""
                if remove_top and len(top_dirs) == 1:
                    prefix = next(iter(top_dirs)) + "/"
                    log(f"🔍 已去除顶层目录: {prefix[:-1]}")

                if subdir_name:
                    target_base = os.path.join(dest_dir, subdir_name)
                    target_dir = target_base
                    counter = 1
                    while os.path.exists(target_dir):
                        target_dir = f"{target_base}_{counter:03d}"
                        counter += 1
                    log(f"📁 项目子目录: {os.path.basename(target_dir)}")
                else:
                    target_dir = dest_dir

                os.makedirs(target_dir, exist_ok=True)

                for member in zf.infolist():
                    if prefix and member.filename == prefix:
                        continue
                    new_name = member.filename[len(prefix):] if prefix else member.filename
                    if not new_name: continue
                    target_path = os.path.join(target_dir, new_name)
                    if member.is_dir():
                        os.makedirs(target_path, exist_ok=True)
                    else:
                        os.makedirs(os.path.dirname(target_path), exist_ok=True)
                        if os.path.exists(target_path):
                            base, ext = os.path.splitext(target_path)
                            i = 1
                            while os.path.exists(f"{base}_{i:03d}{ext}"):
                                i += 1
                            target_path = f"{base}_{i:03d}{ext}"
                        with zf.open(member) as src, open(target_path, 'wb') as dst:
                            shutil.copyfileobj(src, dst)
                log("✅ 解压完成")
                self.last_extracted_dir = target_dir
                self.project_var.set(os.path.basename(target_dir))
        except Exception as e:
            log(f"❌ 解压失败: {e}")

    # ---------- 打开目录 ----------
    def open_project_dir(self):
        d = self.last_extracted_dir
        if not d or not os.path.isdir(d):
            if self.work_dir and os.path.isdir(self.work_dir):
                d = self.work_dir
            else:
                messagebox.showwarning("警告", "没有可打开的目录")
                return
        try:
            if sys.platform == "win32": os.startfile(d)
            elif sys.platform == "darwin": subprocess.run(["open", d])
            else: subprocess.run(["xdg-open", d])
            log(f"📁 已打开目录: {d}")
        except Exception as e:
            log(f"❌ 无法打开目录: {e}")

    # ---------- 自定义编译指令 ----------
    def custom_build_command(self):
        dlg = Toplevel(self.root)
        dlg.title("自定义编译指令")
        dlg.geometry("500x150")
        dlg.transient(self.root)
        dlg.grab_set()

        frame = ttk.Frame(dlg, padding="10")
        frame.pack(fill=BOTH, expand=True)

        ttk.Label(frame, text="编译指令（支持占位符）:").pack(anchor=W)
        cmd_var = StringVar(value=self.custom_build_cmd)
        ttk.Entry(frame, textvariable=cmd_var, width=60).pack(fill=X, pady=5)
        ttk.Label(frame, text="{work_dir} 或 {project_dir}", foreground="gray").pack(anchor=W)

        def do_copy():
            cmd = cmd_var.get()
            cmd = cmd.replace("{work_dir}", self.work_dir)
            cmd = cmd.replace("{project_dir}", self.last_extracted_dir or self.work_dir)
            self.root.clipboard_clear()
            self.root.clipboard_append(cmd)
            log(f"📋 已复制编译指令: {cmd}")
            self.custom_build_cmd = cmd_var.get()
            self.config["custom_build_cmd"] = self.custom_build_cmd
            save_config(self.config)
            dlg.destroy()

        ttk.Button(frame, text="复制到剪贴板", command=do_copy).pack(side=RIGHT, padx=5)
        ttk.Button(frame, text="取消", command=dlg.destroy).pack(side=RIGHT, padx=5)

    # ---------- 快速上传（当前项目目录） ----------
    @run_in_thread
    def quick_upload(self):
        repo = self.repo_combo.get()
        branch = self.branch_combo.get()
        if not repo:
            log("❌ 请先选择仓库")
            return
        if not self.last_extracted_dir or not os.path.isdir(self.last_extracted_dir):
            log("❌ 没有当前项目目录，请先下载并解压")
            return
        log(f"📤 快速上传: {self.last_extracted_dir} -> {repo}")
        self._do_upload(source_dir=self.last_extracted_dir,
                        target_repo=repo,
                        target_branch=branch,
                        upload_target=self.upload_target)

    # ---------- 手动上传对话框（支持目标路径） ----------
    def show_manual_upload_dialog(self):
        dlg = Toplevel(self.root)
        dlg.title("手动上传")
        dlg.geometry("550x450")
        dlg.transient(self.root)
        dlg.grab_set()

        frame = ttk.Frame(dlg, padding="15")
        frame.pack(fill=BOTH, expand=True)

        row = 0
        source_mode = StringVar(value="folder")
        ttk.Label(frame, text="上传来源:").grid(row=row, column=0, sticky=W, pady=5)
        ttk.Radiobutton(frame, text="文件夹", variable=source_mode, value="folder").grid(row=row, column=1, sticky=W)
        ttk.Radiobutton(frame, text="文件", variable=source_mode, value="file").grid(row=row, column=2, sticky=W)
        row += 1

        dir_var = StringVar()
        file_var = StringVar()
        target_path_var = StringVar()

        folder_frame = ttk.Frame(frame)
        folder_frame.grid(row=row, column=0, columnspan=3, sticky=EW, pady=5)
        ttk.Label(folder_frame, text="文件夹:").pack(side=LEFT)
        ttk.Entry(folder_frame, textvariable=dir_var, width=40).pack(side=LEFT, fill=X, expand=True)
        ttk.Button(folder_frame, text="浏览...", command=lambda: self._browse_source_dir(dir_var)).pack(side=LEFT, padx=2)
        ttk.Button(folder_frame, text="当前项目", command=lambda: dir_var.set(self.last_extracted_dir or "")).pack(side=LEFT)

        file_frame = ttk.Frame(frame)
        file_frame.grid(row=row, column=0, columnspan=3, sticky=EW, pady=5)
        ttk.Label(file_frame, text="文件:").pack(side=LEFT)
        ttk.Entry(file_frame, textvariable=file_var, width=40).pack(side=LEFT, fill=X, expand=True)
        ttk.Button(file_frame, text="浏览...", command=lambda: self._browse_source_file(file_var)).pack(side=LEFT, padx=2)
        row += 1

        # 目标路径（仅文件模式 + 根目录上传时显示）
        target_path_frame = ttk.Frame(frame)
        target_path_frame.grid(row=row, column=0, columnspan=3, sticky=EW, pady=5)
        ttk.Label(target_path_frame, text="目标路径 (可选，如 .github/workflows/):").pack(side=LEFT)
        ttk.Entry(target_path_frame, textvariable=target_path_var, width=30).pack(side=LEFT, fill=X, expand=True)

        def on_source_mode_change(*args):
            if source_mode.get() == "folder":
                folder_frame.grid()
                file_frame.grid_remove()
            else:
                folder_frame.grid_remove()
                file_frame.grid()
            toggle_target_path_visibility()
        source_mode.trace_add("write", on_source_mode_change)

        # 目标仓库
        ttk.Label(frame, text="目标仓库 (owner/repo):").grid(row=row, column=0, sticky=W, pady=5)
        repo_var = StringVar(value=self.repo_full)
        repo_combo = ttk.Combobox(frame, textvariable=repo_var, values=self.repos_cache, width=40)
        repo_combo.grid(row=row, column=1, sticky=EW, padx=5)
        ttk.Button(frame, text="当前", command=lambda: repo_var.set(self.repo_full)).grid(row=row, column=2)
        row += 1

        ttk.Label(frame, text="分支:").grid(row=row, column=0, sticky=W, pady=5)
        branch_var = StringVar(value=self.branch)
        branch_combo = ttk.Combobox(frame, textvariable=branch_var, width=20)
        def load_branches_for_target(*args):
            target = repo_var.get()
            if target and "/" in target:
                self._load_branches_async(target, branch_combo, branch_var)
        repo_combo.bind('<<ComboboxSelected>>', load_branches_for_target)
        branch_combo.grid(row=row, column=1, sticky=W, padx=5)
        ttk.Button(frame, text="加载", command=load_branches_for_target).grid(row=row, column=2)
        row += 1

        ttk.Label(frame, text="上传位置:").grid(row=row, column=0, sticky=W, pady=5)
        upload_var = StringVar(value=self.upload_target)
        rb_root = ttk.Radiobutton(frame, text="仓库根目录", variable=upload_var, value="repo_root")
        rb_root.grid(row=row, column=1, sticky=W)
        row += 1
        rb_release = ttk.Radiobutton(frame, text="Release 资产", variable=upload_var, value="release")
        rb_release.grid(row=row, column=1, sticky=W)
        row += 1

        def toggle_target_path_visibility(*args):
            # 只有选择“文件”模式且上传到“仓库根目录”时才显示目标路径
            if source_mode.get() == "file" and upload_var.get() == "repo_root":
                target_path_frame.grid()
            else:
                target_path_frame.grid_remove()
        upload_var.trace_add("write", toggle_target_path_visibility)
        on_source_mode_change()  # 初始显示

        btn_frame_bottom = ttk.Frame(frame)
        btn_frame_bottom.grid(row=row, column=0, columnspan=3, pady=20)
        ttk.Button(btn_frame_bottom, text="开始上传", command=lambda: self._start_manual_upload(
            dlg, source_mode, dir_var, file_var, target_path_var, repo_var, branch_var, upload_var
        )).pack(side=LEFT, padx=5)
        ttk.Button(btn_frame_bottom, text="取消", command=dlg.destroy).pack(side=LEFT, padx=5)

    def _browse_source_dir(self, var):
        d = filedialog.askdirectory()
        if d: var.set(d)

    def _browse_source_file(self, var):
        f = filedialog.askopenfilename(title="选择文件")
        if f: var.set(f)

    def _start_manual_upload(self, dlg, source_mode, dir_var, file_var, target_path_var, repo_var, branch_var, upload_var):
        repo = repo_var.get().strip()
        branch = branch_var.get().strip()
        upload_target = upload_var.get()
        target_path = target_path_var.get().strip()

        if not repo or "/" not in repo:
            messagebox.showerror("错误", "请输入有效的目标仓库")
            return
        if source_mode.get() == "folder":
            source_dir = dir_var.get()
            if not source_dir or not os.path.isdir(source_dir):
                messagebox.showerror("错误", "请选择有效的文件夹")
                return
            zip_path = None
        else:
            zip_path = file_var.get()
            if not zip_path or not os.path.isfile(zip_path):
                messagebox.showerror("错误", "请选择有效的文件")
                return
            source_dir = None
            # 如果上传到根目录且指定了目标路径，则将其拼接到文件名前面
            if upload_target == "repo_root" and target_path:
                # 将目标路径和文件名合成为一个临时路径，但实际 API 需要路径参数，这里先保存到临时变量
                # 我们将在 _do_upload_with_params 中处理
                pass
        dlg.destroy()
        threading.Thread(target=self._do_upload_with_params, args=(
            source_dir, zip_path, repo, branch, upload_target, target_path
        ), daemon=True).start()

    def _load_branches_async(self, repo, combo, var):
        def task():
            try:
                headers = {"Authorization": f"token {self.token}"}
                resp = requests.get(f"https://api.github.com/repos/{repo}/branches", headers=headers)
                resp.raise_for_status()
                branches = [b["name"] for b in resp.json()]
                self.root.after(0, lambda: self._update_branch_combo_in_dialog(combo, var, branches))
            except:
                pass
        threading.Thread(target=task, daemon=True).start()

    def _update_branch_combo_in_dialog(self, combo, var, branches):
        combo['values'] = branches
        if branches:
            if "main" in branches:
                var.set("main")
            elif "master" in branches:
                var.set("master")
            else:
                var.set(branches[0])

    # ===================== 通用上传核心 =====================
    def _do_upload(self, source_dir=None, zip_path=None, target_repo=None, target_branch=None, upload_target=None, target_path=None):
        if zip_path is None and source_dir is not None:
            zip_path = self._pack_directory(source_dir)
            if not zip_path: return
        if not zip_path:
            log("❌ 没有可上传的文件")
            return
        target_repo = target_repo or self.repo_full
        target_branch = target_branch or self.branch
        upload_target = upload_target or self.upload_target
        self._do_upload_impl(zip_path, target_repo, target_branch, upload_target, target_path)

    def _do_upload_with_params(self, source_dir, zip_path, target_repo, target_branch, upload_target, target_path):
        if zip_path:
            log(f"📤 上传文件: {os.path.basename(zip_path)} -> {target_repo}")
        else:
            log(f"📦 打包文件夹: {source_dir}")
            zip_path = self._pack_directory(source_dir)
            if not zip_path: return
        self._do_upload_impl(zip_path, target_repo, target_branch, upload_target, target_path)

    def _do_upload_impl(self, zip_path, target_repo, target_branch, upload_target, target_path=None):
        if upload_target == "repo_root":
            self._upload_to_repo_root(zip_path, target_repo, target_branch, target_path)
        else:
            self._upload_to_release(zip_path, target_repo)

    def _pack_directory(self, source_dir):
        exclude_dirs = {'.git', '.idea', '.gradle', '.vscode', '__pycache__', 'build', 'app/build', '.svn'}
        exclude_ext = {'.pyc', '.class', '.o', '.obj', '.exe', '.dll', '.so', '.dylib', '.log'}
        def should_exclude(name, path):
            if name.startswith('.'): return True
            if name in exclude_dirs and os.path.isdir(path): return True
            if os.path.isfile(path) and os.path.splitext(name)[1].lower() in exclude_ext: return True
            return False

        dir_name = os.path.basename(source_dir.rstrip('/\\'))
        zip_name = f"{dir_name}.zip"
        tmp_zip = os.path.join(tempfile.gettempdir(), zip_name)
        try:
            with zipfile.ZipFile(tmp_zip, 'w', zipfile.ZIP_DEFLATED) as zf:
                for root, dirs, files in os.walk(source_dir):
                    dirs[:] = [d for d in dirs if not should_exclude(d, os.path.join(root, d))]
                    for file in files:
                        file_path = os.path.join(root, file)
                        if should_exclude(file, file_path): continue
                        arcname = os.path.relpath(file_path, source_dir)
                        zf.write(file_path, arcname)
            file_size = os.path.getsize(tmp_zip) / (1024*1024)
            log(f"✅ 打包完成 ({file_size:.1f} MB)")
            return tmp_zip
        except Exception as e:
            log(f"❌ 打包失败: {e}")
            return None

    def _upload_to_repo_root(self, zip_path, repo, branch, target_path=None):
        owner, repo_name = repo.split("/", 1)
        file_name = os.path.basename(zip_path)
        # 处理目标路径：如果指定了路径，则拼接到文件名前面
        if target_path:
            # 确保路径以 / 结尾
            target_path = target_path.strip().rstrip('/') + '/'
            remote_path = target_path + file_name
        else:
            remote_path = file_name
        try:
            headers = {"Authorization": f"token {self.token}"}
            api_url = f"https://api.github.com/repos/{owner}/{repo_name}/contents/{remote_path}"
            resp = requests.get(api_url, headers=headers, params={"ref": branch})
            sha = resp.json().get("sha") if resp.status_code == 200 else None
            with open(zip_path, 'rb') as f:
                b64_content = base64.b64encode(f.read()).decode()
            payload = {"message": f"Upload {remote_path}", "content": b64_content, "branch": branch}
            if sha: payload["sha"] = sha
            resp = requests.put(api_url, headers=headers, json=payload)
            resp.raise_for_status()
            log(f"✅ 上传到仓库根目录成功: {remote_path}")
        except Exception as e:
            log(f"❌ 上传到根目录失败: {e}")

    def _upload_to_release(self, zip_path, repo):
        owner, repo_name = repo.split("/", 1)
        file_name = os.path.basename(zip_path)
        file_size = os.path.getsize(zip_path) / (1024*1024)
        try:
            headers = {
                "Authorization": f"token {self.token}",
                "Accept": "application/vnd.github.v3+json"
            }
            release_tag = f"upload-{time.strftime('%Y%m%d-%H%M%S')}"
            resp = requests.get(f"https://api.github.com/repos/{owner}/{repo_name}/releases/tags/{release_tag}",
                                headers=headers)
            if resp.status_code == 200:
                release = resp.json()
                upload_url = release["upload_url"].replace("{?name,label}", "")
            else:
                create_data = {
                    "tag_name": release_tag,
                    "name": f"手动上传 #{release_tag}",
                    "body": f"文件: {file_name}",
                    "draft": False,
                    "prerelease": False
                }
                resp = requests.post(f"https://api.github.com/repos/{owner}/{repo_name}/releases",
                                     headers=headers, json=create_data)
                resp.raise_for_status()
                release = resp.json()
                upload_url = release["upload_url"].replace("{?name,label}", "")

            with open(zip_path, 'rb') as f:
                asset_headers = {
                    "Authorization": f"token {self.token}",
                    "Content-Type": "application/zip" if file_name.endswith('.zip') else "application/octet-stream"
                }
                params = {"name": file_name}
                resp = requests.post(upload_url, headers=asset_headers, params=params, data=f)
                resp.raise_for_status()
            log(f"✅ 上传到 Release 成功! {release['html_url']}")
            log(f"   文件: {file_name} ({file_size:.1f} MB)")
        except Exception as e:
            log(f"❌ 上传到 Release 失败: {e}")

    def on_close(self):
        self.config.update({
            "work_dir": self.work_var.get(),
            "trae_path": self.trae_var.get(),
            "auto_extract": self.auto_extract,
            "delete_zip_after_extract": self.delete_zip_after_extract,
            "upload_target": self.upload_target,
            "font_scale": self.font_scale,
            "custom_build_cmd": self.custom_build_cmd
        })
        self.config.pop("project_dir", None)
        self.config.pop("dl_dir", None)
        save_config(self.config)
        self.root.destroy()

if __name__ == "__main__":
    root = Tk()
    app = App(root)
    root.mainloop()