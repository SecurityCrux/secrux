import { LogOut } from "lucide-react"
import { useTranslation } from "react-i18next"

import { Button } from "@/components/ui/button"
import { LanguageSwitcher } from "@/components/language/language-switcher"
import { ThemeToggle } from "@/components/theme/theme-toggle"
import { Logo } from "@/components/logo"
import { useAuth } from "@/hooks/use-auth"

export function AppHeader() {
  const { user, logout } = useAuth()
  const { t } = useTranslation()

  return (
    <header className="flex items-center justify-between border-b bg-card px-6 py-3">
      <Logo />
      <div className="flex items-center gap-2">
        <div className="text-sm text-muted-foreground">
          {user?.username}
        </div>
        <LanguageSwitcher />
        <ThemeToggle />
        <Button variant="ghost" size="icon" onClick={logout} aria-label={t("common.logout")}>
          <LogOut className="h-4 w-4" />
        </Button>
      </div>
    </header>
  )
}
