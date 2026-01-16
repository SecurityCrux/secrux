import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { Spinner } from "@/components/ui/spinner"
import { useAuth } from "@/hooks/use-auth"
import { listIamRoles } from "@/services/iam-role-service"
import {
  assignUserRoles,
  createUser,
  getUser,
  listUsers,
  resetUserPassword,
  updateUserProfile,
  updateUserStatus,
} from "@/services/user-service"
import type { IamRoleSummaryResponse, UserSummary } from "@/types/api"

export function UserManagementPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [search, setSearch] = useState("")
  const [page, setPage] = useState({ limit: 20, offset: 0 })
  const [editingUserId, setEditingUserId] = useState<string | null>(null)
  const [editForm, setEditForm] = useState({
    username: "",
    email: "",
    phone: "",
    name: "",
    roleIds: [] as string[],
  })

  const usersQuery = useQuery({
    queryKey: ["users", search, page],
    queryFn: () => listUsers({ search: search.trim() || undefined, ...page }),
  })

  const createMutation = useMutation({
    mutationFn: createUser,
    onSuccess: () => {
      void usersQuery.refetch()
    },
  })

  const statusMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => updateUserStatus(id, { enabled }),
    onSuccess: () => void usersQuery.refetch(),
  })

  const resetMutation = useMutation({
    mutationFn: ({ id, password }: { id: string; password: string }) =>
      resetUserPassword(id, { password, temporary: false }),
  })

  const rolesQuery = useQuery({
    queryKey: ["iam-roles-for-users"],
    queryFn: () => listIamRoles({ limit: 200, offset: 0 }),
    enabled: Boolean(editingUserId),
  })

  const detailQuery = useQuery({
    queryKey: ["user-detail", editingUserId],
    queryFn: () => getUser(editingUserId as string),
    enabled: Boolean(editingUserId),
  })

  const profileMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: typeof editForm }) =>
      updateUserProfile(id, {
        username: payload.username || null,
        email: payload.email || null,
        phone: payload.phone || null,
        name: payload.name || null,
      }),
    onSuccess: () => void usersQuery.refetch(),
  })

  const assignRolesMutation = useMutation({
    mutationFn: ({ id, roleIds }: { id: string; roleIds: string[] }) => assignUserRoles(id, { roleIds }),
    onSuccess: () => void usersQuery.refetch(),
  })

  const users = usersQuery.data?.items ?? []
  const total = usersQuery.data?.total ?? 0
  const limit = usersQuery.data?.limit ?? page.limit
  const offset = usersQuery.data?.offset ?? page.offset
  const pageIndex = total === 0 ? 0 : Math.floor(offset / Math.max(1, limit))
  const pageCount = Math.max(1, Math.ceil(Math.max(1, total) / Math.max(1, limit)))

  const canManage = (user?.roles ?? []).includes("user:manage")

  const handleCreate = async () => {
    const username = prompt(t("users.prompts.username") || "Username")
    if (!username) return
    const email = prompt(t("users.prompts.email") || "Email (optional)") || undefined
    const password = prompt(t("users.prompts.password") || "Initial password") || undefined
    if (!password) {
      alert(t("users.errors.passwordRequired") || "Password is required")
      return
    }
    createMutation.mutate({ username, email, password: password || undefined, enabled: true })
  }

  const handleReset = (id: string) => {
    const pwd = prompt(t("users.prompts.resetPassword") || "New password")
    if (!pwd) return
    resetMutation.mutate({ id, password: pwd })
  }

  const handlePage = (direction: "prev" | "next") => {
    if (direction === "prev" && offset === 0) return
    if (direction === "next" && offset + limit >= total) return
    const nextOffset = direction === "prev" ? Math.max(0, offset - limit) : Math.min(total, offset + limit)
    setPage((prev) => ({ ...prev, offset: nextOffset }))
  }

  if (!canManage) {
    return <p className="text-sm text-muted-foreground">{t("users.accessDenied")}</p>
  }

  const roles = rolesQuery.data?.items ?? []

  const openEdit = (id: string) => {
    setEditingUserId(id)
    setEditForm({ username: "", email: "", phone: "", name: "", roleIds: [] })
  }

  const closeEdit = () => {
    setEditingUserId(null)
    setEditForm({ username: "", email: "", phone: "", name: "", roleIds: [] })
  }

  useEffect(() => {
    if (!detailQuery.data || !editingUserId) return
    setEditForm({
      username: detailQuery.data.username ?? "",
      email: detailQuery.data.email ?? "",
      phone: detailQuery.data.phone ?? "",
      name: detailQuery.data.name ?? "",
      roleIds: detailQuery.data.roleIds ?? [],
    })
  }, [detailQuery.data, editingUserId])

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("users.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("users.subtitle")}</p>
        </div>
        <Button onClick={handleCreate} disabled={createMutation.isPending}>
          {createMutation.isPending ? t("users.creating") : t("users.create")}
        </Button>
      </div>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("users.listTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              placeholder={t("users.searchPlaceholder")}
              value={search}
              onChange={(e) => {
                setSearch(e.target.value)
                setPage((prev) => ({ ...prev, offset: 0 }))
              }}
            />
            <Button variant="outline" onClick={() => usersQuery.refetch()} disabled={usersQuery.isFetching}>
              {t("common.refresh")}
            </Button>
          </div>
          {usersQuery.isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Spinner size={16} /> {t("common.loading")}
            </div>
          ) : usersQuery.isError ? (
            <p className="text-sm text-destructive">{t("users.error")}</p>
          ) : users.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("users.empty")}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="py-2 text-left">{t("users.columns.username")}</th>
                    <th className="py-2 text-left">{t("users.columns.email")}</th>
                    <th className="py-2 text-left">{t("users.columns.enabled")}</th>
                    <th className="py-2 text-right">{t("users.columns.actions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((item: UserSummary) => (
                    <tr key={item.id} className="border-b last:border-b-0">
                      <td className="py-2 align-top">{item.username}</td>
                      <td className="py-2 align-top text-muted-foreground">{item.email ?? "—"}</td>
                      <td className="py-2 align-top">
                        <span className="text-xs">{item.enabled ? t("common.enabled") : t("common.disabled")}</span>
                      </td>
                      <td className="py-2 align-top text-right">
                        <div className="flex justify-end gap-2">
                          <Button size="sm" variant="outline" onClick={() => openEdit(item.id)}>
                            {t("users.actions.edit")}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={statusMutation.isPending}
                            onClick={() => statusMutation.mutate({ id: item.id, enabled: !item.enabled })}
                          >
                            {item.enabled ? t("users.actions.disable") : t("users.actions.enable")}
                          </Button>
                          <Button size="sm" variant="outline" onClick={() => handleReset(item.id)}>
                            {resetMutation.isPending ? t("users.actions.resetting") : t("users.actions.resetPassword")}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="mt-3 flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  {t("common.pageStatus", {
                    page: total === 0 ? 0 : pageIndex + 1,
                    total: pageCount,
                  })}
                </span>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" onClick={() => handlePage("prev")} disabled={offset === 0}>
                    {t("common.previous")}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handlePage("next")}
                    disabled={offset + limit >= total}
                  >
                    {t("common.next")}
                  </Button>
                </div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={Boolean(editingUserId)} onOpenChange={(next) => (!next ? closeEdit() : null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t("users.editTitle")}</DialogTitle>
          </DialogHeader>

          {detailQuery.isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Spinner size={16} /> {t("common.loading")}
            </div>
          ) : detailQuery.isError || !detailQuery.data ? (
            <p className="text-sm text-destructive">{t("users.error")}</p>
          ) : (
            <div className="space-y-4">
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-2">
                  <Label htmlFor="edit-username">{t("users.columns.username")}</Label>
                  <Input
                    id="edit-username"
                    value={editForm.username}
                    onChange={(e) => setEditForm((prev) => ({ ...prev, username: e.target.value }))}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-email">{t("users.columns.email")}</Label>
                  <Input
                    id="edit-email"
                    value={editForm.email}
                    onChange={(e) => setEditForm((prev) => ({ ...prev, email: e.target.value }))}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-phone">{t("users.fields.phone")}</Label>
                  <Input
                    id="edit-phone"
                    value={editForm.phone}
                    onChange={(e) => setEditForm((prev) => ({ ...prev, phone: e.target.value }))}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-name">{t("users.fields.name")}</Label>
                  <Input
                    id="edit-name"
                    value={editForm.name}
                    onChange={(e) => setEditForm((prev) => ({ ...prev, name: e.target.value }))}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <Label>{t("users.fields.roles")}</Label>
                <div className="max-h-56 space-y-2 overflow-auto rounded-md border p-3">
                  {rolesQuery.isLoading ? (
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <Spinner size={16} /> {t("common.loading")}
                    </div>
                  ) : roles.length === 0 ? (
                    <p className="text-sm text-muted-foreground">{t("users.rolesEmpty")}</p>
                  ) : (
                    roles.map((role: IamRoleSummaryResponse) => {
                      const checked = editForm.roleIds.includes(role.roleId)
                      return (
                        <label key={role.roleId} className="flex cursor-pointer items-center gap-2 text-sm">
                          <Checkbox
                            checked={checked}
                            onChange={() =>
                              setEditForm((prev) => ({
                                ...prev,
                                roleIds: checked
                                  ? prev.roleIds.filter((id) => id !== role.roleId)
                                  : [...prev.roleIds, role.roleId],
                              }))
                            }
                          />
                          <div className="flex flex-col">
                            <span className="font-medium">
                              {role.name}{" "}
                              {role.builtIn ? (
                                <span className="ml-1 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                                  {t("roles.builtIn")}
                                </span>
                              ) : null}
                            </span>
                            <span className="text-xs text-muted-foreground">{role.key}</span>
                          </div>
                        </label>
                      )
                    })
                  )}
                </div>
              </div>

              <div className="rounded-md border bg-muted/20 px-3 py-2 text-xs text-muted-foreground">
                {t("users.fields.effectivePermissions")}:{" "}
                <span className="font-mono">{(detailQuery.data.permissions ?? []).slice(0, 6).join(", ") || "—"}</span>
                {(detailQuery.data.permissions ?? []).length > 6 ? " ..." : null}
              </div>
            </div>
          )}

          <DialogFooter>
            <Button variant="outline" onClick={closeEdit}>
              {t("common.cancel")}
            </Button>
            <Button
              onClick={async () => {
                if (!editingUserId) return
                await profileMutation.mutateAsync({ id: editingUserId, payload: editForm })
                await assignRolesMutation.mutateAsync({ id: editingUserId, roleIds: editForm.roleIds })
                closeEdit()
              }}
              disabled={profileMutation.isPending || assignRolesMutation.isPending}
            >
              {profileMutation.isPending || assignRolesMutation.isPending ? t("common.saving") : t("common.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
