const tabButtons = document.querySelectorAll("[data-tab-target]")
const toast = document.querySelector("[data-toast]")

function showToast(message) {
  if (!toast) return
  toast.textContent = message
  toast.classList.add("is-visible")
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => toast.classList.remove("is-visible"), 2600)
}

function activateTab(button) {
  const targetId = button.dataset.tabTarget
  const surface = button.closest("[data-surface]")
  if (!targetId || !surface) return

  // 作者: long；桌面端和移动端共用一套切页规则，保证主应用始终只有“设备/设置”两种一级页面。
  surface.querySelectorAll("[data-tab-target]").forEach((item) => {
    item.classList.toggle("is-active", item === button)
  })
  surface.querySelectorAll(".page-view, .mobile-page").forEach((page) => {
    page.classList.toggle("is-active", page.id === targetId)
  })
}

function openRemoteWindow(button) {
  const device = button.dataset.device || "Remote Device"
  const surface = button.dataset.surface || "desktop"
  const url = new URL("./remote-window.html", window.location.href)
  url.searchParams.set("device", device)
  url.searchParams.set("surface", surface)

  const features = surface === "mobile"
    ? "width=430,height=820,noopener,noreferrer"
    : "width=1180,height=760,noopener,noreferrer"

  // 作者: long；远程控制被建模为独立窗口，避免会话工具栏和调试信息挤占设备列表主页面。
  const opened = window.open(url.toString(), "_blank", features)
  if (opened) {
    showToast(`已打开 ${device} 的远程窗口`)
  } else {
    showToast(`浏览器拦截了新窗口，可从顶部“远程窗口”入口查看`)
  }
}

function syncDebugPanel(toggle) {
  const host = toggle.closest("[data-surface]") || document
  const panel = host.querySelector("[data-debug-panel]")
  if (!panel) return
  panel.classList.toggle("is-visible", toggle.checked)
}

tabButtons.forEach((button) => {
  button.addEventListener("click", () => activateTab(button))
})

document.querySelectorAll("[data-remote]").forEach((button) => {
  if (button.tagName.toLowerCase() === "a") {
    button.addEventListener("click", () => {
      const device = button.dataset.device || "Remote Device"
      showToast(`正在打开 ${device} 的远程窗口`)
    })
    return
  }
  button.addEventListener("click", () => openRemoteWindow(button))
})

document.querySelectorAll("[data-debug-toggle]").forEach((toggle) => {
  syncDebugPanel(toggle)
  toggle.addEventListener("change", () => syncDebugPanel(toggle))
})

if (document.body.dataset.page === "session") {
  const params = new URLSearchParams(window.location.search)
  const device = params.get("device") || "Office-PC"
  const title = document.querySelector("[data-session-title]")
  if (title) title.textContent = `${device} - 远程会话`

  document.querySelectorAll("[data-close-window]").forEach((button) => {
    button.addEventListener("click", () => {
      window.close()
      showToast?.("会话已断开")
    })
  })
}
