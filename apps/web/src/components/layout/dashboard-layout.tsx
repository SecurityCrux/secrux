import { Outlet } from "react-router-dom"
import { useState } from "react"
import { AppHeader } from "@/components/layout/app-header"
import { Sidebar } from "@/components/layout/sidebar"

export function DashboardLayout() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <AppHeader />
      <div className="flex flex-1">
        <Sidebar collapsed={sidebarCollapsed} onToggle={() => setSidebarCollapsed((prev) => !prev)} />
        <main className="flex flex-1 flex-col gap-4 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
