import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext, filedialog, font as tkfont
import os
import json
import sys
import requests
import base64
from urllib.parse import quote

# ==================== 配置文件 ====================
CONFIG_FILE = os.path.expanduser("~/.github_browser_config.json")

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                return json.load(f)
        except:
            pass
    return {}

def save_config(config):
    try:
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
            json.dump(config, f, indent=2)
    except:
        pass

# ==================== GitHub API 封装 ====================
class GitHubAPI:
    BASE_URL = "https://api.github.com"

    def __init__(self, token):
        self.token = token.strip()
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"token {self.token}",
            "Accept": "application/vnd.github.v3+json"
        })

    def _check(self, resp, codes=(200, 201, 204)):
        if resp.status_code in codes:
            return resp
        msg = resp.json().get("message", resp.text) if resp.text else str(resp.status_code)
        raise Exception(f"API 错误 {resp.status_code}: {msg}")

    def get_user(self):
        resp = self.session.get(f"{self.BASE_URL}/user")
        self._check(resp)
        return resp.json()

    def list_user_repos(self, sort="updated"):
        repos = []
        page = 1
        while True:
            resp = self.session.get(f"{self.BASE_URL}/user/repos",
                                    params={"per_page": 100, "page": page, "sort": sort})
            self._check(resp)
            data = resp.json()
            if not data:
                break
            repos.extend(data)
            if len(data) < 100:
                break
            page += 1
        return repos

    def search_repos(self, query):
        repos = []
        page = 1
        while True:
            resp = self.session.get(f"{self.BASE_URL}/search/repositories",
                                    params={"q": query, "per_page": 100, "page": page})
            self._check(resp)
            data = resp.json()
            items = data.get("items", [])
            if not items:
                break
            repos.extend(items)
            if len(items) < 100:
                break
            page += 1
        return repos

    def get_default_branch(self, owner, repo):
        resp = self.session.get(f"{self.BASE_URL}/repos/{owner}/{repo}")
        self._check(resp)
        return resp.json().get("default_branch", "main")

    def get_repo_tree(self, owner, repo, branch="main", recursive=True):
        ref_resp = self.session.get(f"{self.BASE_URL}/repos/{owner}/{repo}/git/ref/heads/{branch}")
        self._check(ref_resp)
        commit_sha = ref_resp.json()["object"]["sha"]
        params = {"recursive": 1} if recursive else {}
        resp = self.session.get(f"{self.BASE_URL}/repos/{owner}/{repo}/git/trees/{commit_sha}", params=params)
        self._check(resp)
        return resp.json().get("tree", [])

    def get_file_content(self, owner, repo, path, branch="main"):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/contents/{quote(path)}"
        resp = self.session.get(url, params={"ref": branch})
        self._check(resp)
        data = resp.json()
        if isinstance(data, list):
            return None
        encoding = data.get("encoding")
        content = data.get("content", "")
        if encoding == "base64":
            decoded = base64.b64decode(content)
            try:
                return decoded.decode("utf-8")
            except UnicodeDecodeError:
                return decoded
        return content

    # ------ Actions API ------
    def list_workflows(self, owner, repo):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/workflows"
        resp = self.session.get(url)
        self._check(resp)
        return resp.json().get("workflows", [])

    def dispatch_workflow(self, owner, repo, workflow_id, ref):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches"
        payload = {"ref": ref}
        resp = self.session.post(url, json=payload)
        self._check(resp, codes=(204,))
        return True

    def list_workflow_runs(self, owner, repo, workflow_id, status=None, per_page=30, page=1):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/workflows/{workflow_id}/runs"
        params = {"per_page": per_page, "page": page}
        if status:
            params["status"] = status
        resp = self.session.get(url, params=params)
        self._check(resp)
        return resp.json().get("workflow_runs", [])

    def get_run(self, owner, repo, run_id):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/runs/{run_id}"
        resp = self.session.get(url)
        self._check(resp)
        return resp.json()

    def get_run_jobs(self, owner, repo, run_id):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/runs/{run_id}/jobs"
        resp = self.session.get(url)
        self._check(resp)
        return resp.json().get("jobs", [])

    def get_job_log(self, owner, repo, job_id):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/jobs/{job_id}/logs"
        resp = self.session.get(url, allow_redirects=False)
        if resp.status_code in (301, 302):
            log_url = resp.headers.get("Location")
            if log_url:
                log_resp = requests.get(log_url)
                if log_resp.status_code == 200:
                    return log_resp.text
                else:
                    raise Exception(f"下载日志失败: {log_resp.status_code}")
        elif resp.status_code == 200:
            return resp.text
        else:
            self._check(resp)
            return resp.text

    def list_run_artifacts(self, owner, repo, run_id):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/runs/{run_id}/artifacts"
        resp = self.session.get(url)
        self._check(resp)
        return resp.json().get("artifacts", [])

    def download_artifact(self, owner, repo, artifact_id, save_path):
        url = f"{self.BASE_URL}/repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip"
        resp = self.session.get(url, allow_redirects=False)
        if resp.status_code in (301, 302):
            dl_url = resp.headers.get("Location")
            if dl_url:
                dl_resp = requests.get(dl_url, stream=True)
                if dl_resp.status_code == 200:
                    with open(save_path, 'wb') as f:
                        for chunk in dl_resp.iter_content(chunk_size=8192):
                            f.write(chunk)
                    return True
                else:
                    raise Exception(f"下载失败: {dl_resp.status_code}")
        else:
            raise Exception(f"获取下载链接失败: {resp.status_code}")

# ==================== 登录窗口 ====================
class LoginWindow(tk.Toplevel):
    def __init__(self, parent, config):
        super().__init__(parent)
        self.config = config
        self.result = None
        self.title("登录 GitHub")
        self.geometry("420x200")
        self.resizable(False, False)

        ttk.Label(self, text="请输入 GitHub Personal Access Token:").pack(pady=10)

        token_frame = ttk.Frame(self)
        token_frame.pack(pady=5)
        self.token_var = tk.StringVar(value=config.get("token", ""))
        self.token_entry = ttk.Entry(token_frame, textvariable=self.token_var, width=40, show="*")
        self.token_entry.pack(side=tk.LEFT, padx=5)
        self.show_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(token_frame, text="显示", variable=self.show_var,
                        command=self.toggle).pack(side=tk.LEFT)

        self.status_label = ttk.Label(self, text="", foreground="gray")
        self.status_label.pack(pady=5)

        btn_frame = ttk.Frame(self)
        btn_frame.pack(pady=10)
        self.login_btn = ttk.Button(btn_frame, text="登录", command=self.login, width=12)
        self.login_btn.pack(side=tk.LEFT, padx=5)
        ttk.Button(btn_frame, text="取消", command=self.quit, width=12).pack(side=tk.LEFT, padx=5)

        self.protocol("WM_DELETE_WINDOW", self.quit)
        self.grab_set()

    def toggle(self):
        self.token_entry.config(show="" if self.show_var.get() else "*")

    def login(self):
        token = self.token_var.get().strip()
        if not token:
            messagebox.showwarning("警告", "Token 不能为空")
            return

        self.login_btn.config(state=tk.DISABLED)
        self.status_label.config(text="正在验证 token...")
        self.update()

        try:
            api = GitHubAPI(token)
            user = api.get_user()
            self.config["token"] = token
            save_config(self.config)
            self.result = (api, user)
            self.destroy()
        except Exception as e:
            self.login_btn.config(state=tk.NORMAL)
            self.status_label.config(text="")
            messagebox.showerror("错误", f"Token 无效或网络错误:\n{str(e)}")

    def quit(self):
        self.result = None
        self.destroy()

# ==================== 主窗口 ====================
class MainWindow(tk.Tk):
    def __init__(self, api, user_info, config):
        super().__init__()
        self.api = api
        self.user_info = user_info
        self.cfg = config

        self.title(f"GitHub 浏览器 - {user_info['login']}")

        self.font_scale = self.cfg.get("font_scale", 1.0)
        self.apply_font_scale()

        width = int(1100 * self.font_scale)
        height = int(700 * self.font_scale)
        self.geometry(f"{width}x{height}")
        self.minsize(900, 650)

        self.current_repo = None
        self.repos_meta = {}
        self.workflows = []
        self.current_workflow_id = None
        self.poll_after_id = None
        self.current_runs = []

        self.create_widgets()
        self.load_my_repos_sync()

        self.protocol("WM_DELETE_WINDOW", self.on_close)

    def apply_font_scale(self):
        default_font = tkfont.nametofont("TkDefaultFont")
        default_font.configure(size=int(default_font.cget("size") * self.font_scale))
        try:
            fixed_font = tkfont.Font(family="Consolas", size=int(9 * self.font_scale))
        except:
            fixed_font = tkfont.Font(family="Courier", size=int(9 * self.font_scale))
        self.fixed_font = fixed_font

    def create_widgets(self):
        main_paned = ttk.PanedWindow(self, orient=tk.HORIZONTAL)
        main_paned.pack(fill=tk.BOTH, expand=True)

        # 左侧 notebook
        left_notebook = ttk.Notebook(main_paned)
        main_paned.add(left_notebook, weight=0)

        # ---------- 标签1：仓库列表 ----------
        repo_tab = ttk.Frame(left_notebook, width=int(280 * self.font_scale))
        left_notebook.add(repo_tab, text="仓库列表")

        ttk.Label(repo_tab, text="仓库列表", font=("", 10, "bold")).pack(pady=5)

        search_frame = ttk.Frame(repo_tab)
        search_frame.pack(fill=tk.X, padx=5, pady=2)
        self.search_var = tk.StringVar(value=self.cfg.get("last_search", ""))
        search_entry = ttk.Entry(search_frame, textvariable=self.search_var)
        search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True)
        search_btn = ttk.Button(search_frame, text="搜索", command=self.search_repos_sync)
        search_btn.pack(side=tk.RIGHT, padx=2)

        action_frame = ttk.Frame(repo_tab)
        action_frame.pack(fill=tk.X, padx=5, pady=2)
        my_repos_btn = ttk.Button(action_frame, text="我的仓库", command=self.load_my_repos_sync)
        my_repos_btn.pack(side=tk.LEFT, padx=2)
        refresh_btn = ttk.Button(action_frame, text="刷新", command=self.load_my_repos_sync)
        refresh_btn.pack(side=tk.LEFT, padx=2)

        self.repo_listbox = tk.Listbox(repo_tab, exportselection=False)
        self.repo_listbox.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self.repo_listbox.bind("<<ListboxSelect>>", self.on_repo_select)

        # ---------- 标签2：目录树 ----------
        tree_tab = ttk.Frame(left_notebook, width=int(280 * self.font_scale))
        left_notebook.add(tree_tab, text="目录树")

        ttk.Label(tree_tab, text="目录树", font=("", 10, "bold")).pack(anchor=tk.W, padx=5, pady=2)

        self.tree = ttk.Treeview(tree_tab, columns=("type",), show="tree headings", selectmode="browse")
        self.tree.heading("#0", text="名称")
        self.tree.column("#0", width=int(250 * self.font_scale))
        self.tree.heading("type", text="类型")
        self.tree.column("type", width=60, anchor=tk.CENTER)
        self.tree.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self.tree.bind("<<TreeviewSelect>>", self.on_tree_select)

        # ---------- 标签3：Actions ----------
        actions_tab = ttk.Frame(left_notebook, width=int(280 * self.font_scale))
        left_notebook.add(actions_tab, text="Actions")

        # 上半部分：Workflow 选择
        wf_top = ttk.Frame(actions_tab)
        wf_top.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(wf_top, text="Workflow:").pack(side=tk.LEFT)
        self.wf_combo = ttk.Combobox(wf_top, state="readonly", width=20)
        self.wf_combo.pack(side=tk.LEFT, padx=2)
        self.wf_combo.bind("<<ComboboxSelected>>", self.on_wf_selected)
        ttk.Button(wf_top, text="刷新", command=self.load_workflows_sync).pack(side=tk.LEFT, padx=2)
        ttk.Button(wf_top, text="触发", command=self.trigger_selected_workflow).pack(side=tk.LEFT, padx=2)

        # 状态筛选
        filter_frame = ttk.Frame(actions_tab)
        filter_frame.pack(fill=tk.X, padx=5, pady=2)
        ttk.Label(filter_frame, text="状态:").pack(side=tk.LEFT)
        self.status_filter = ttk.Combobox(filter_frame, state="readonly", width=12,
                                          values=["所有", "进行中", "成功", "失败"])
        self.status_filter.current(0)
        self.status_filter.pack(side=tk.LEFT, padx=2)
        self.status_filter.bind("<<ComboboxSelected>>", lambda e: self.load_runs_sync())

        # Runs 列表
        self.runs_tree = ttk.Treeview(actions_tab, columns=("id", "status", "time", "action"), show="headings",
                                      selectmode="browse")
        self.runs_tree.heading("id", text="Run ID")
        self.runs_tree.column("id", width=50, anchor=tk.CENTER)
        self.runs_tree.heading("status", text="状态")
        self.runs_tree.column("status", width=60, anchor=tk.CENTER)
        self.runs_tree.heading("time", text="时间")
        self.runs_tree.column("time", width=80, anchor=tk.CENTER)
        self.runs_tree.heading("action", text="下载")
        self.runs_tree.column("action", width=50, anchor=tk.CENTER)
        self.runs_tree.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self.runs_tree.bind("<<TreeviewSelect>>", self.on_run_select)
        self.runs_tree.bind("<Button-1>", self.on_run_click)

        # 右侧：内容显示
        self.content_text = scrolledtext.ScrolledText(main_paned, wrap=tk.WORD, state=tk.DISABLED,
                                                      font=self.fixed_font)
        main_paned.add(self.content_text, weight=1)

        # 状态栏
        self.status_var = tk.StringVar(value="就绪")
        status_bar = ttk.Label(self, textvariable=self.status_var, relief=tk.SUNKEN, anchor=tk.W)
        status_bar.pack(fill=tk.X, side=tk.BOTTOM)

        # 菜单
        menubar = tk.Menu(self)
        self.config(menu=menubar)
        settings_menu = tk.Menu(menubar, tearoff=0)
        menubar.add_cascade(label="设置", menu=settings_menu)
        settings_menu.add_command(label="偏好设置", command=self.open_settings)

    # ---------- 数据加载（同步） ----------
    def load_my_repos_sync(self):
        self.set_status("加载中...", cursor="watch")
        try:
            repos = self.api.list_user_repos()
            self.repo_listbox.delete(0, tk.END)
            self.repos_meta = {}
            for repo in repos:
                full_name = repo["full_name"]
                self.repo_listbox.insert(tk.END, full_name)
                self.repos_meta[full_name] = repo
            if not repos:
                self.repo_listbox.insert(tk.END, "没有仓库")
        except Exception as e:
            self.repo_listbox.delete(0, tk.END)
            self.repo_listbox.insert(tk.END, f"加载失败: {e}")
        finally:
            self.set_status("就绪", cursor="")

    def search_repos_sync(self):
        query = self.search_var.get().strip()
        if not query:
            return
        self.set_status("搜索中...", cursor="watch")
        try:
            repos = self.api.search_repos(query)
            self.repo_listbox.delete(0, tk.END)
            self.repos_meta = {}
            for repo in repos:
                full_name = repo["full_name"]
                self.repo_listbox.insert(tk.END, full_name)
                self.repos_meta[full_name] = repo
            if not repos:
                self.repo_listbox.insert(tk.END, "无匹配结果")
        except Exception as e:
            self.repo_listbox.delete(0, tk.END)
            self.repo_listbox.insert(tk.END, f"搜索失败: {e}")
        finally:
            self.set_status("就绪", cursor="")

    def load_repo_tree_sync(self, owner, repo, branch):
        self.tree.delete(*self.tree.get_children())
        self.tree.insert("", tk.END, text="加载中...", values=("dir",))
        self.set_status("加载目录树...", cursor="watch")
        try:
            entries = self.api.get_repo_tree(owner, repo, branch)
            self.tree.delete(*self.tree.get_children())
            self.populate_tree(entries)
        except Exception as e:
            self.tree.delete(*self.tree.get_children())
            self.tree.insert("", tk.END, text=f"加载失败: {e}")
        finally:
            self.set_status("就绪", cursor="")

    def display_file_content_sync(self, owner, repo, path, branch):
        self.clear_content()
        self.set_content_text("加载中...")
        self.set_status("加载文件内容...", cursor="watch")
        try:
            content = self.api.get_file_content(owner, repo, path, branch)
            if content is None:
                self.set_content_text("无法获取该文件内容（可能为目录或过大）")
            elif isinstance(content, bytes):
                self.set_content_text("[二进制文件，无法直接预览]")
            else:
                self.set_content_text(content)
        except Exception as e:
            self.set_content_text(f"加载出错: {e}")
        finally:
            self.set_status("就绪", cursor="")

    # ---------- Actions 操作 ----------
    def load_workflows_sync(self):
        if not self.current_repo:
            messagebox.showwarning("提示", "请先在「仓库列表」中选择一个仓库")
            return
        self.set_status("加载 workflows...", cursor="watch")
        try:
            wfs = self.api.list_workflows(
                self.current_repo["owner"],
                self.current_repo["repo"]
            )
            self.workflows = wfs
            # 更新下拉框
            wf_names = [wf["name"] for wf in wfs]
            self.wf_combo["values"] = wf_names
            if wf_names:
                self.wf_combo.current(0)
                self.on_wf_selected()
            else:
                self.current_workflow_id = None
                self.runs_tree.delete(*self.runs_tree.get_children())
            self.set_status(f"已加载 {len(wfs)} 个 workflow", cursor="")
        except Exception as e:
            self.set_status("加载失败", cursor="")
            messagebox.showerror("错误", f"无法加载 workflows: {e}")

    def on_wf_selected(self, event=None):
        idx = self.wf_combo.current()
        if idx >= 0 and idx < len(self.workflows):
            wf = self.workflows[idx]
            self.current_workflow_id = wf["id"]
            self.load_runs_sync()
        else:
            self.current_workflow_id = None

    def load_runs_sync(self):
        if not self.current_workflow_id:
            return
        # 映射中文筛选到 API status
        status_map = {
            "所有": None,
            "进行中": "in_progress",
            "成功": "success",
            "失败": "failure"
        }
        filter_text = self.status_filter.get()
        api_status = status_map.get(filter_text, None)
        self.set_status("加载 runs...", cursor="watch")
        try:
            runs = self.api.list_workflow_runs(
                self.current_repo["owner"],
                self.current_repo["repo"],
                self.current_workflow_id,
                status=api_status,
                per_page=30
            )
            self.current_runs = runs
            self._refresh_runs_list()
            self.set_status(f"加载了 {len(runs)} 个 runs", cursor="")
        except Exception as e:
            self.set_status("加载失败", cursor="")
            messagebox.showerror("错误", f"无法加载 runs: {e}")

    def _refresh_runs_list(self):
        # 批量更新后恢复显示，减少重绘次数
        self.runs_tree.configure(displaycolumns=())  # 临时隐藏列
        self.runs_tree.delete(*self.runs_tree.get_children())
        for run in self.current_runs:
            run_id = run["id"]
            status = run["status"]
            if status == "completed":
                conclusion = run.get("conclusion", status)
                status_display = conclusion.capitalize()
            else:
                status_display = status.capitalize()
            created_at = run["created_at"]
            if "T" in created_at:
                time_str = created_at.split("T")[1].split("Z")[0] if "Z" in created_at else created_at.split("T")[1]
            else:
                time_str = created_at
            can_download = (status == "completed" and run.get("conclusion") == "success")
            action_text = "下载" if can_download else ""
            item = self.runs_tree.insert("", tk.END, values=(run_id, status_display, time_str, action_text))
            if can_download:
                self.runs_tree.set(item, "action", "下载")
        self.runs_tree.configure(displaycolumns=("id", "status", "time", "action"))

    def trigger_selected_workflow(self):
        if not self.current_repo or not self.current_workflow_id:
            messagebox.showwarning("提示", "请先选择仓库和工作流")
            return
        self.clear_content()
        self.set_content_text("正在触发 workflow...\n")
        self.set_status("触发中...", cursor="watch")
        try:
            self.api.dispatch_workflow(
                self.current_repo["owner"],
                self.current_repo["repo"],
                self.current_workflow_id,
                self.current_repo["branch"]
            )
            self.append_content("触发成功，等待运行开始...\n")
            self.set_status("触发成功", cursor="")
            # 触发后刷新 runs 列表
            self.after(2000, self.load_runs_sync)
        except Exception as e:
            self.append_content(f"触发失败: {e}\n")
            self.set_status("触发失败", cursor="")

    def on_run_select(self, event):
        """选中 run 时显示日志（右侧）"""
        sel = self.runs_tree.selection()
        if not sel:
            return
        item = sel[0]
        run_id = self.runs_tree.item(item, "values")[0]
        self.show_run_log(int(run_id))

    def show_run_log(self, run_id):
        if not self.current_repo:
            return
        self.clear_content()
        self.set_content_text(f"正在加载 Run #{run_id} 日志...\n")
        self.set_status("加载日志...", cursor="watch")
        try:
            jobs = self.api.get_run_jobs(
                self.current_repo["owner"],
                self.current_repo["repo"],
                run_id
            )
            log_text = ""
            for job in jobs:
                job_log = self.api.get_job_log(
                    self.current_repo["owner"],
                    self.current_repo["repo"],
                    job["id"]
                )
                log_text += f"--- Job: {job['name']} ---\n{job_log}\n"
            self.set_content_text(log_text)
            self.set_status("日志加载完毕", cursor="")
        except Exception as e:
            self.set_content_text(f"加载日志出错: {e}")
            self.set_status("加载失败", cursor="")

    def on_run_click(self, event):
        """处理下载按钮点击"""
        region = self.runs_tree.identify_region(event.x, event.y)
        col = self.runs_tree.identify_column(event.x)
        if region != "cell" or col != "#3":  # action 列是 #3
            return
        item = self.runs_tree.identify_row(event.y)
        if not item:
            return
        action_text = self.runs_tree.item(item, "action")
        if not action_text or action_text[0] != "下载":
            return
        run_id = int(self.runs_tree.item(item, "values")[0])
        self.download_apk_for_run(run_id)

    def download_apk_for_run(self, run_id):
        if not self.current_repo:
            return
        self.set_status("获取 artifacts...", cursor="watch")
        try:
            artifacts = self.api.list_run_artifacts(
                self.current_repo["owner"],
                self.current_repo["repo"],
                run_id
            )
            # 过滤 APK 相关 artifacts（名称包含 .apk 或 apk）
            apk_artifacts = [a for a in artifacts if "apk" in a["name"].lower()]
            if not apk_artifacts:
                # 如果没有明确 APK，就允许下载所有 artifacts
                apk_artifacts = artifacts
            if not apk_artifacts:
                messagebox.showinfo("提示", "该 Run 没有可下载的 artifacts")
                self.set_status("就绪", cursor="")
                return
            # 如果有多个，让用户选择
            if len(apk_artifacts) > 1:
                choices = [f"{a['name']} ({a['size_in_bytes']} bytes)" for a in apk_artifacts]
                dialog = tk.Toplevel(self)
                dialog.title("选择要下载的 Artifact")
                dialog.geometry("300x200")
                dialog.transient(self)
                dialog.grab_set()
                lb = tk.Listbox(dialog)
                for c in choices:
                    lb.insert(tk.END, c)
                lb.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
                selected_index = tk.IntVar(value=-1)

                def select():
                    idx = lb.curselection()
                    if idx:
                        selected_index.set(idx[0])
                        dialog.destroy()
                    else:
                        messagebox.showwarning("提示", "请选择一个 artifact")
                ttk.Button(dialog, text="下载", command=select).pack(pady=5)
                self.wait_window(dialog)
                if selected_index.get() < 0:
                    self.set_status("就绪", cursor="")
                    return
                artifact = apk_artifacts[selected_index.get()]
            else:
                artifact = apk_artifacts[0]

            # 选择保存路径
            ext = ".apk" if artifact["name"].endswith(".apk") else ".zip"
            save_path = filedialog.asksaveasfilename(
                defaultextension=ext,
                filetypes=[("APK/ZIP 文件", f"*{ext}"), ("所有文件", "*.*")],
                initialfile=artifact["name"]
            )
            if not save_path:
                self.set_status("就绪", cursor="")
                return
            self.set_status("下载中...", cursor="watch")
            self.api.download_artifact(
                self.current_repo["owner"],
                self.current_repo["repo"],
                artifact["id"],
                save_path
            )
            self.set_status("下载完成", cursor="")
            messagebox.showinfo("下载完成", f"文件已保存到:\n{save_path}")
        except Exception as e:
            self.set_status("下载失败", cursor="")
            messagebox.showerror("错误", f"下载 artifacts 失败: {e}")

    # ---------- 通用辅助方法 ----------
    def set_status(self, text, cursor=None):
        self.status_var.set(text)
        if cursor is not None:
            self.config(cursor=cursor)
        # 不再调用 self.update()，避免触发底层崩溃

    def append_content(self, text):
        self.content_text.config(state=tk.NORMAL)
        self.content_text.insert(tk.END, text)
        self.content_text.see(tk.END)
        self.content_text.config(state=tk.DISABLED)
        # 不再调用 self.update()

    def set_content_text(self, text):
        self.content_text.config(state=tk.NORMAL)
        self.content_text.delete(1.0, tk.END)
        self.content_text.insert(1.0, text)
        self.content_text.config(state=tk.DISABLED)

    def clear_content(self):
        self.content_text.config(state=tk.NORMAL)
        self.content_text.delete(1.0, tk.END)
        self.content_text.config(state=tk.DISABLED)

    def populate_tree(self, tree_entries):
        node_map = {"": ""}
        sorted_entries = sorted(tree_entries, key=lambda x: x["path"])
        for entry in sorted_entries:
            path = entry["path"]
            typ = entry["type"]
            parent_path = "/".join(path.split("/")[:-1]) if "/" in path else ""
            name = path.split("/")[-1]
            if parent_path not in node_map:
                parts = parent_path.split("/")
                for i in range(1, len(parts) + 1):
                    sub_path = "/".join(parts[:i])
                    if sub_path not in node_map:
                        parent = "/".join(parts[:i-1]) if i > 1 else ""
                        parent_id = node_map.get(parent, "")
                        node_id = self.tree.insert(parent_id, tk.END, text=parts[i-1], values=("dir",), open=False)
                        node_map[sub_path] = node_id
            parent_id = node_map[parent_path]
            if typ == "tree":
                node_id = self.tree.insert(parent_id, tk.END, text=name, values=("dir",), open=False)
                node_map[path] = node_id
            else:
                self.tree.insert(parent_id, tk.END, text=name, values=("file",))

    def get_item_path(self, item):
        parts = []
        while item:
            parts.append(self.tree.item(item, "text"))
            item = self.tree.parent(item)
        return "/".join(reversed(parts))

    # ---------- 事件处理 ----------
    def on_repo_select(self, event):
        sel = self.repo_listbox.curselection()
        if not sel:
            return
        full_name = self.repo_listbox.get(sel[0])
        repo_info = self.repos_meta.get(full_name)
        if not repo_info:
            return
        owner = repo_info["owner"]["login"]
        repo_name = repo_info["name"]
        try:
            branch = self.api.get_default_branch(owner, repo_name)
        except:
            branch = "main"
        self.current_repo = {"owner": owner, "repo": repo_name, "branch": branch}
        self.clear_content()
        self.load_repo_tree_sync(owner, repo_name, branch)
        left_notebook = self.tree.master.master
        left_notebook.select(self.tree.master)
        self.cfg["last_repo"] = f"{owner}/{repo_name}"
        save_config(self.cfg)

    def on_tree_select(self, event):
        sel = self.tree.selection()
        if not sel:
            return
        item = sel[0]
        values = self.tree.item(item, "values")
        if not values or values[0] != "file":
            self.clear_content()
            return
        path = self.get_item_path(item)
        if not path or not self.current_repo:
            return
        self.display_file_content_sync(
            self.current_repo["owner"],
            self.current_repo["repo"],
            path,
            self.current_repo["branch"]
        )

    def open_settings(self):
        dialog = tk.Toplevel(self)
        dialog.title("偏好设置")
        dialog.geometry("300x150")
        dialog.transient(self)
        dialog.grab_set()
        frame = ttk.Frame(dialog, padding="10")
        frame.pack(fill=tk.BOTH, expand=True)
        ttk.Label(frame, text="界面字体缩放").grid(row=0, column=0, sticky=tk.W, pady=5)
        scale_var = tk.DoubleVar(value=self.font_scale)
        scale = ttk.Scale(frame, from_=0.8, to=2.0, variable=scale_var, orient=tk.HORIZONTAL, length=180)
        scale.grid(row=0, column=1, padx=5)
        scale_label = ttk.Label(frame, text=f"{self.font_scale:.1f}倍")
        scale_label.grid(row=0, column=2, padx=5)
        scale_var.trace_add("write", lambda *args: scale_label.config(text=f"{scale_var.get():.1f}倍"))

        def save():
            new_scale = scale_var.get()
            if abs(new_scale - self.font_scale) > 0.01:
                self.cfg["font_scale"] = new_scale
                save_config(self.cfg)
                if messagebox.askyesno("重启程序", "字体缩放已更改，需重启生效。是否立即重启？"):
                    python = sys.executable
                    os.execl(python, python, *sys.argv)
            dialog.destroy()

        ttk.Button(frame, text="保存", command=save).place(x=60, y=80)
        ttk.Button(frame, text="取消", command=dialog.destroy).place(x=150, y=80)

    def on_close(self):
        if self.poll_after_id:
            self.after_cancel(self.poll_after_id)
        self.cfg["font_scale"] = self.font_scale
        save_config(self.cfg)
        self.destroy()

# ==================== 程序入口 ====================
def main():
    config = load_config()
    root = tk.Tk()
    root.withdraw()

    token = config.get("token", "").strip()
    if token:
        try:
            api = GitHubAPI(token)
            user = api.get_user()
            root.destroy()
            app = MainWindow(api, user, config)
            app.mainloop()
            return
        except Exception:
            pass

    login = LoginWindow(root, config)
    root.wait_window(login)

    if login.result is None:
        root.destroy()
        return

    api, user = login.result
    root.destroy()
    app = MainWindow(api, user, config)
    app.mainloop()

if __name__ == "__main__":
    main()