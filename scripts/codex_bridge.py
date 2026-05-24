#!/usr/bin/env python3
"""
Codex CLI Bridge - ACP Sub-Agent Adapter
v1.0 - 讓 Codex CLI 0.133.0 成為 Hermes Agent 的 sub-agent

架構：
Main Agent (Hermes) <-> JSON-RPC/stdio <-> codex_bridge.py <-> subprocess <-> Codex CLI

使用方法：
1. 在 Hermes Agent config 中設置 ACP command:
   python3 /mnt/d/TrafficSafety/scripts/codex_bridge.py
2. Hermes 會透過 stdio 傳送 JSON-RPC 請求
3. Bridge 轉換成 codex exec 命令
4. 結果包裝回 JSON-RPC 回應
"""

import sys
import json
import subprocess
import os
import re

# 設定工作目錄 (應用主目錄)
WORK_DIR = "/mnt/d/TrafficSafety"


def log_stderr(msg):
    """輸出日誌到 stderr (不會干擾 JSON-RPC stdout)"""
    print(f"[CodexBridge] {msg}", file=sys.stderr, flush=True)


def send_jsonrpc(id, result=None, error=None):
    """發送 JSON-RPC 回應到 stdout"""
    response = {"jsonrpc": "2.0", "id": id}
    if error:
        response["error"] = {"code": -32000, "message": str(error)}
    else:
        response["result"] = result
    print(json.dumps(response), flush=True)
    log_stderr(f"Sent response for id={id}")


def parse_task_from_prompt(prompt):
    """從 Hermes prompt 解析實際要執行的任務"""
    # 提取 context 和 goal
    context_match = re.search(r'Context:\s*(.+?)(?=\n\n|Goal:|$)', prompt, re.DOTALL | re.IGNORECASE)
    goal_match = re.search(r'Goal:\s*(.+?)(?=\n\n|$)', prompt, re.DOTALL | re.IGNORECASE)
    
    task_parts = []
    if context_match:
        task_parts.append(f"Context: {context_match.group(1).strip()}")
    if goal_match:
        task_parts.append(f"Task: {goal_match.group(1).strip()}")
    
    return "\n\n".join(task_parts) if task_parts else prompt


def call_codex(task):
    """呼叫 Codex CLI 執行任務"""
    try:
        # 將 task 中的特殊字元轉義
        safe_task = task.replace('"', '\\"').replace("'", "'\\''")
        
        # 使用 codex exec 執行任務
        cmd = [
            "codex", 
            "exec", 
            "--dangerously-auto-apply",  # 自動應用變更
            "--quiet",  # 減少輸出
            task
        ]
        
        log_stderr(f"Executing: {' '.join(cmd[:5])}...")
        
        result = subprocess.run(
            cmd,
            cwd=WORK_DIR,
            capture_output=True,
            text=True,
            timeout=300,  # 5 分鐘超時
            encoding='utf-8'
        )
        
        return {
            "stdout": result.stdout,
            "stderr": result.stderr,
            "returncode": result.returncode,
            "success": result.returncode == 0
        }
        
    except subprocess.TimeoutExpired:
        return {
            "success": False,
            "error": "Codex CLI 執行超時 (>5分鐘)"
        }
    except Exception as e:
        return {
            "success": False,
            "error": str(e)
        }


def process_initialize(id, params):
    """處理 ACP Initialize 請求"""
    log_stderr("Processing initialize request")
    return {
        "name": "codex-subagent",
        "version": "1.0.0",
        "capabilities": {
            "tools": ["codex_exec", "file_read", "file_write", "shell"]
        }
    }


def process_tools_call(id, params):
    """處理 tool call 請求"""
    tool_name = params.get("name", "unknown")
    tool_args = params.get("arguments", {})
    
    log_stderr(f"Tool call: {tool_name} with args: {list(tool_args.keys())}")
    
    if tool_name == "codex_exec":
        # 執行 Codex CLI
        prompt = tool_args.get("prompt", "")
        task = parse_task_from_prompt(prompt)
        
        result = call_codex(task)
        
        return {
            "content": [
                {
                    "type": "text",
                    "text": f"Codex CLI 執行結果:\n\nstdout:\n{result.get('stdout', '')}\n\nstderr:\n{result.get('stderr', '')}"
                }
            ],
            "isError": not result.get("success", False),
            "codex_result": result
        }
        
    elif tool_name == "file_read":
        # 讀取檔案
        path = tool_args.get("path", "")
        try:
            full_path = os.path.join(WORK_DIR, path) if not os.path.isabs(path) else path
            with open(full_path, 'r', encoding='utf-8') as f:
                content = f.read()
            return {"content": [{"type": "text", "text": content}], "isError": False}
        except Exception as e:
            return {"content": [{"type": "text", "text": str(e)}], "isError": True}
    
    elif tool_name == "file_write":
        # 寫入檔案
        path = tool_args.get("path", "")
        content = tool_args.get("content", "")
        try:
            full_path = os.path.join(WORK_DIR, path) if not os.path.isabs(path) else path
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            with open(full_path, 'w', encoding='utf-8') as f:
                f.write(content)
            return {"content": [{"type": "text", "text": f"File written: {path}"}], "isError": False}
        except Exception as e:
            return {"content": [{"type": "text", "text": str(e)}], "isError": True}
    
    elif tool_name == "shell":
        # 執行 shell 命令
        command = tool_args.get("command", "")
        try:
            result = subprocess.run(
                command, 
                shell=True, 
                cwd=WORK_DIR,
                capture_output=True, 
                text=True, 
                timeout=60
            )
            output = f"stdout:\n{result.stdout}\n\nstderr:\n{result.stderr}"
            return {"content": [{"type": "text", "text": output}], "isError": result.returncode != 0}
        except Exception as e:
            return {"content": [{"type": "text", "text": str(e)}], "isError": True}
    
    else:
        return {"content": [{"type": "text", "text": f"Unknown tool: {tool_name}"}], "isError": True}


def main():
    """主要事件迴圈"""
    log_stderr(f"Codex Bridge started (PID: {os.getpid()})")
    log_stderr(f"Work directory: {WORK_DIR}")
    log_stderr("Waiting for JSON-RPC requests...")
    
    while True:
        try:
            # 讀取一行 JSON
            line = sys.stdin.readline()
            if not line:
                log_stderr("EOF received, exiting")
                break
            
            line = line.strip()
            if not line:
                continue
            
            log_stderr(f"Received: {line[:200]}...")
            
            # 解析 JSON-RPC
            try:
                request = json.loads(line)
            except json.JSONDecodeError as e:
                log_stderr(f"JSON parse error: {e}")
                send_jsonrpc(None, error=f"Invalid JSON: {e}")
                continue
            
            req_id = request.get("id")
            method = request.get("method", "")
            params = request.get("params", {})
            
            # 處理不同方法
            if method == "initialize":
                result = process_initialize(req_id, params)
                send_jsonrpc(req_id, result=result)
                
            elif method == "session/new":
                # Hermes ACP 會話初始化
                log_stderr(f"Session new request: {params}")
                send_jsonrpc(req_id, result={
                    "session_id": f"codex-{os.getpid()}",
                    "status": "ready"
                })
                
            elif method == "session/close":
                log_stderr(f"Session close request: {params}")
                send_jsonrpc(req_id, result={"status": "closed"})
                
            elif method == "tools/call" or method == "tools/invoke":
                result = process_tools_call(req_id, params)
                send_jsonrpc(req_id, result=result)
                
            elif method == "ping":
                send_jsonrpc(req_id, result={"pong": True})
                
            else:
                log_stderr(f"Unknown method: {method}")
                send_jsonrpc(req_id, error=f"Unknown method: {method}")
                
        except KeyboardInterrupt:
            log_stderr("Interrupted, exiting")
            break
        except Exception as e:
            log_stderr(f"Error in main loop: {e}")
            try:
                send_jsonrpc(None, error=str(e))
            except:
                pass


if __name__ == "__main__":
    main()
