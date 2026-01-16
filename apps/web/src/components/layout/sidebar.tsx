import { NavLink, useLocation } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { PanelLeftClose, PanelLeftOpen } from "lucide-react"

import { navItems, type NavItem } from "@/config/navigation"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/hooks/use-auth"

type SidebarProps = {
  collapsed: boolean
  onToggle: () => void
}

function isAllowedByRoles(item: NavItem, roles: string[]) {
  return !item.requiredRoles || item.requiredRoles.every((role) => roles.includes(role))
}

function filterNavItems(items: NavItem[], roles: string[]): NavItem[] {
  return items
    .filter((item) => isAllowedByRoles(item, roles))
    .map((item) => ({
      ...item,
      children: item.children ? filterNavItems(item.children, roles) : undefined,
    }))
}

function isPathActive(pathname: string, href: string) {
  if (href === "/") return pathname === "/"
  return pathname === href || pathname.startsWith(`${href}/`)
}

function isItemActive(pathname: string, item: NavItem): boolean {
  if (isPathActive(pathname, item.href)) return true
  return (item.children ?? []).some((child) => isItemActive(pathname, child))
}

export function Sidebar({ collapsed, onToggle }: SidebarProps) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const { pathname } = useLocation()
  const roles = user?.roles ?? []
  const ToggleIcon = collapsed ? PanelLeftOpen : PanelLeftClose
  const allowedItems = filterNavItems(navItems, roles)

  return (
    <aside
      className={cn(
        "hidden border-r bg-card/30 transition-[width] duration-200 lg:block",
        collapsed ? "w-16" : "w-60"
      )}
    >
      <div className="flex h-full flex-col">
        <div className="flex items-center justify-end border-b px-2 py-2">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={onToggle}
            aria-label={t(collapsed ? "common.expandSidebar" : "common.collapseSidebar")}
          >
            <ToggleIcon className="h-4 w-4" />
          </Button>
        </div>
        <nav className={cn("flex flex-1 flex-col gap-1 p-3", collapsed && "items-center p-2")}>
          {allowedItems.map((item) => {
            const active = isItemActive(pathname, item)
            const children = item.children ?? []
            return (
              <div key={item.href} className="w-full space-y-1">
                <NavLink
                  to={item.href}
                  className={({ isActive }) =>
                    cn(
                      "flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition hover:bg-accent hover:text-accent-foreground",
                      (isActive || active) && "bg-accent text-accent-foreground",
                      collapsed && "justify-center px-2"
                    )
                  }
                  end={item.href === "/"}
                >
                  <item.icon className="h-4 w-4" />
                  <span className={cn("truncate", collapsed && "sr-only")}>{t(item.labelKey)}</span>
                </NavLink>

                {children.map((child) => (
                  <NavLink
                    key={child.href}
                    to={child.href}
                    className={({ isActive }) =>
                      cn(
                        "flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition hover:bg-accent hover:text-accent-foreground",
                        isActive && "bg-accent text-accent-foreground",
                        collapsed ? "justify-center px-2" : "pl-10"
                      )
                    }
                    end={child.href === "/"}
                  >
                    <child.icon className={cn("h-4 w-4", !collapsed && "h-3.5 w-3.5")} />
                    <span className={cn("truncate", collapsed && "sr-only")}>{t(child.labelKey)}</span>
                  </NavLink>
                ))}
              </div>
            )
          })}
        </nav>
      </div>
    </aside>
  )
}
