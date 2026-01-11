import { LanguageSwitcher } from "@/components/language/language-switcher"
import { ThemeToggle } from "@/components/theme/theme-toggle"
import { Logo } from "@/components/logo"
import { useTranslation } from "react-i18next"

export function AuthLayout({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="flex items-center justify-between px-6 py-4">
        <Logo />
        <div className="flex items-center gap-2">
          <LanguageSwitcher />
          <ThemeToggle />
        </div>
      </header>
      <main className="flex flex-1 flex-col items-center justify-center px-4 py-8">
        <div className="mx-auto w-full max-w-md">
          <div className="mb-6 text-center">
            <p className="text-sm text-muted-foreground">{t("auth.subtitle")}</p>
          </div>
          {children}
        </div>
      </main>
    </div>
  )
}

