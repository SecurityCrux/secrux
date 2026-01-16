import { useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Spinner } from "@/components/ui/spinner"
import { useAuth } from "@/hooks/use-auth"
import { cn } from "@/lib/utils"
import { fetchPermissionCatalog } from "@/services/iam-permission-service"
import {
  createIamRole,
  deleteIamRole,
  getIamRole,
  listIamRoles,
  setIamRolePermissions,
  updateIamRole,
} from "@/services/iam-role-service"
import type { IamRoleCreatePayload, IamRoleSummaryResponse, PermissionCatalogResponse } from "@/types/api"

type RoleDialogState =
  | { open: false }
  | { open: true; mode: "create" }
  | { open: true; mode: "edit"; role: IamRoleSummaryResponse }
  | { open: true; mode: "permissions"; role: IamRoleSummaryResponse }

export function RoleManagementPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const canManage = (user?.roles ?? []).includes("user:manage")

  const [search, setSearch] = useState("")
  const [page, setPage] = useState({ limit: 20, offset: 0 })
  const [dialog, setDialog] = useState<RoleDialogState>({ open: false })

  const rolesQuery = useQuery({
    queryKey: ["iam-roles", search, page],
    queryFn: () => listIamRoles({ search: search.trim() || undefined, ...page }),
    enabled: canManage,
  })

  const permissionsQuery = useQuery({
    queryKey: ["iam-permissions"],
    queryFn: fetchPermissionCatalog,
    enabled: canManage,
  })

  const createMutation = useMutation({
    mutationFn: createIamRole,
    onSuccess: () => void rolesQuery.refetch(),
  })

  const updateMutation = useMutation({
    mutationFn: ({ roleId, payload }: { roleId: string; payload: { name: string; description?: string | null } }) =>
      updateIamRole(roleId, payload),
    onSuccess: () => void rolesQuery.refetch(),
  })

  const permissionsMutation = useMutation({
    mutationFn: ({ roleId, permissions }: { roleId: string; permissions: string[] }) =>
      setIamRolePermissions(roleId, { permissions }),
    onSuccess: () => void rolesQuery.refetch(),
  })

  const deleteMutation = useMutation({
    mutationFn: deleteIamRole,
    onSuccess: () => void rolesQuery.refetch(),
  })

  const roles = rolesQuery.data?.items ?? []
  const total = rolesQuery.data?.total ?? 0
  const limit = rolesQuery.data?.limit ?? page.limit
  const offset = rolesQuery.data?.offset ?? page.offset
  const pageIndex = total === 0 ? 0 : Math.floor(offset / Math.max(1, limit))
  const pageCount = Math.max(1, Math.ceil(Math.max(1, total) / Math.max(1, limit)))

  const handlePage = (direction: "prev" | "next") => {
    if (direction === "prev" && offset === 0) return
    if (direction === "next" && offset + limit >= total) return
    const nextOffset = direction === "prev" ? Math.max(0, offset - limit) : Math.min(total, offset + limit)
    setPage((prev) => ({ ...prev, offset: nextOffset }))
  }

  if (!canManage) {
    return <p className="text-sm text-muted-foreground">{t("roles.accessDenied")}</p>
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("roles.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("roles.subtitle")}</p>
        </div>
        <Button onClick={() => setDialog({ open: true, mode: "create" })}>{t("roles.create")}</Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("roles.listTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              placeholder={t("roles.searchPlaceholder")}
              value={search}
              onChange={(e) => {
                setSearch(e.target.value)
                setPage((prev) => ({ ...prev, offset: 0 }))
              }}
            />
            <Button variant="outline" onClick={() => void rolesQuery.refetch()} disabled={rolesQuery.isFetching}>
              {t("common.refresh")}
            </Button>
          </div>

          {rolesQuery.isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Spinner size={16} /> {t("common.loading")}
            </div>
          ) : rolesQuery.isError ? (
            <p className="text-sm text-destructive">{t("roles.error")}</p>
          ) : roles.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("roles.empty")}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="py-2 text-left">{t("roles.columns.name")}</th>
                    <th className="py-2 text-left">{t("roles.columns.key")}</th>
                    <th className="py-2 text-left">{t("roles.columns.description")}</th>
                    <th className="py-2 text-right">{t("roles.columns.actions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {roles.map((role) => (
                    <tr key={role.roleId} className="border-b last:border-b-0">
                      <td className="py-2 align-top">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{role.name}</span>
                          {role.builtIn ? (
                            <span className="rounded-md bg-muted px-2 py-0.5 text-[10px] text-muted-foreground">
                              {t("roles.builtIn")}
                            </span>
                          ) : null}
                        </div>
                      </td>
                      <td className="py-2 align-top text-xs text-muted-foreground">{role.key}</td>
                      <td className="py-2 align-top text-xs text-muted-foreground">{role.description ?? "—"}</td>
                      <td className="py-2 align-top text-right">
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setDialog({ open: true, mode: "edit", role })}
                          >
                            {t("roles.actions.edit")}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setDialog({ open: true, mode: "permissions", role })}
                          >
                            {t("roles.actions.permissions")}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={role.builtIn || deleteMutation.isPending}
                            onClick={() => {
                              if (role.builtIn) return
                              if (!confirm(t("roles.actions.confirmDelete"))) return
                              deleteMutation.mutate(role.roleId)
                            }}
                          >
                            {t("roles.actions.delete")}
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

      <RoleDialog
        state={dialog}
        onClose={() => setDialog({ open: false })}
        permissionsCatalog={permissionsQuery.data ?? null}
        onCreate={(payload) => createMutation.mutate(payload, { onSuccess: () => setDialog({ open: false }) })}
        onUpdate={(roleId, payload) =>
          updateMutation.mutate({ roleId, payload }, { onSuccess: () => setDialog({ open: false }) })
        }
        onSetPermissions={(roleId, permissions) =>
          permissionsMutation.mutate({ roleId, permissions }, { onSuccess: () => setDialog({ open: false }) })
        }
      />
    </div>
  )
}

function RoleDialog({
  state,
  onClose,
  permissionsCatalog,
  onCreate,
  onUpdate,
  onSetPermissions,
}: {
  state: RoleDialogState
  onClose: () => void
  permissionsCatalog: PermissionCatalogResponse | null
  onCreate: (payload: IamRoleCreatePayload) => void
  onUpdate: (roleId: string, payload: { name: string; description?: string | null }) => void
  onSetPermissions: (roleId: string, permissions: string[]) => void
}) {
  const { t } = useTranslation()
  const open = state.open
  const mode = state.open ? state.mode : null

  const initial = useMemo(() => {
    if (!state.open) return null
    if (state.mode === "edit" || state.mode === "permissions") return state.role
    return null
  }, [state])

  const [key, setKey] = useState("")
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [permissionFilter, setPermissionFilter] = useState("")
  const [selectedPermissions, setSelectedPermissions] = useState<string[]>([])

  const allPermissions = permissionsCatalog?.permissions ?? []
  const filteredPermissions = useMemo(() => {
    const term = permissionFilter.trim().toLowerCase()
    if (!term) return allPermissions
    return allPermissions.filter((p) => p.toLowerCase().includes(term))
  }, [allPermissions, permissionFilter])

  const title =
    mode === "create"
      ? t("roles.dialog.createTitle")
      : mode === "edit"
        ? t("roles.dialog.editTitle")
        : mode === "permissions"
          ? t("roles.dialog.permissionsTitle")
          : ""

  const resetForm = () => {
    setKey("")
    setName("")
    setDescription("")
    setPermissionFilter("")
    setSelectedPermissions([])
  }

  const openChanged = (nextOpen: boolean) => {
    if (!nextOpen) {
      resetForm()
      onClose()
      return
    }
    if (!state.open) return
    if (state.mode === "edit") {
      setName(state.role.name)
      setDescription(state.role.description ?? "")
    }
    if (state.mode === "permissions") {
      setSelectedPermissions([])
    }
  }

  const canSubmit =
    mode === "create"
      ? key.trim().length > 1 && name.trim().length > 0
      : mode === "edit"
        ? name.trim().length > 0 && initial != null
        : mode === "permissions"
          ? initial != null
          : false

  const submit = () => {
    if (!canSubmit || !state.open) return
    if (state.mode === "create") {
      onCreate({
        key: key.trim(),
        name: name.trim(),
        description: description.trim() || null,
        permissions: selectedPermissions,
      })
      return
    }
    if (state.mode === "edit") {
      onUpdate(state.role.roleId, { name: name.trim(), description: description.trim() || null })
      return
    }
    if (state.mode === "permissions") {
      onSetPermissions(state.role.roleId, selectedPermissions)
    }
  }

  return (
    <Dialog open={open} onOpenChange={openChanged}>
      <DialogContent className={cn(mode === "permissions" ? "max-w-2xl" : null)}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>

        {mode === "create" ? (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="role-key">{t("roles.fields.key")}</Label>
              <Input id="role-key" value={key} onChange={(e) => setKey(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="role-name">{t("roles.fields.name")}</Label>
              <Input id="role-name" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="role-desc">{t("roles.fields.description")}</Label>
              <Input id="role-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
            <PermissionPicker
              title={t("roles.fields.permissions")}
              allPermissions={filteredPermissions}
              filter={permissionFilter}
              onFilterChange={setPermissionFilter}
              selected={selectedPermissions}
              onChange={setSelectedPermissions}
              loading={!permissionsCatalog}
            />
          </div>
        ) : null}

        {mode === "edit" ? (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="role-name">{t("roles.fields.name")}</Label>
              <Input id="role-name" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="role-desc">{t("roles.fields.description")}</Label>
              <Input id="role-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
          </div>
        ) : null}

        {mode === "permissions" && initial ? (
          <PermissionDialogContent
            roleId={initial.roleId}
            roleName={initial.name}
            catalog={permissionsCatalog}
            selected={selectedPermissions}
            setSelected={setSelectedPermissions}
          />
        ) : null}

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button onClick={submit} disabled={!canSubmit}>
            {t("common.save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function PermissionDialogContent({
  roleId,
  roleName,
  catalog,
  selected,
  setSelected,
}: {
  roleId: string
  roleName: string
  catalog: PermissionCatalogResponse | null
  selected: string[]
  setSelected: (permissions: string[]) => void
}) {
  const { t } = useTranslation()
  const { data, isLoading } = useQuery({
    queryKey: ["iam-role", roleId],
    queryFn: () => getIamRole(roleId),
    enabled: Boolean(roleId),
  })

  const [filter, setFilter] = useState("")

  const allPermissions = catalog?.permissions ?? []
  const rolePermissions = data?.permissions ?? []
  const current = selected.length > 0 ? selected : rolePermissions

  const filtered = useMemo(() => {
    const term = filter.trim().toLowerCase()
    if (!term) return allPermissions
    return allPermissions.filter((p) => p.toLowerCase().includes(term))
  }, [allPermissions, filter])

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Spinner size={16} /> {t("common.loading")}
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <div className="text-sm text-muted-foreground">
        {t("roles.dialog.permissionsHint", { name: roleName })}
      </div>
      <PermissionPicker
        title={t("roles.fields.permissions")}
        allPermissions={filtered}
        filter={filter}
        onFilterChange={setFilter}
        selected={current}
        onChange={setSelected}
        loading={!catalog}
      />
    </div>
  )
}

function PermissionPicker({
  title,
  allPermissions,
  filter,
  onFilterChange,
  selected,
  onChange,
  loading,
}: {
  title: string
  allPermissions: string[]
  filter: string
  onFilterChange: (value: string) => void
  selected: string[]
  onChange: (next: string[]) => void
  loading: boolean
}) {
  const { t } = useTranslation()
  const selectedSet = useMemo(() => new Set(selected), [selected])

  const toggle = (permission: string) => {
    if (selectedSet.has(permission)) {
      onChange(selected.filter((p) => p !== permission))
    } else {
      onChange([...selected, permission])
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <Label>{title}</Label>
        <div className="text-xs text-muted-foreground">
          {t("roles.selectedCount", { count: selected.length })}
        </div>
      </div>
      <Input placeholder={t("roles.permissionFilter")} value={filter} onChange={(e) => onFilterChange(e.target.value)} />
      <div className="max-h-64 space-y-2 overflow-auto rounded-md border p-3">
        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} /> {t("common.loading")}
          </div>
        ) : allPermissions.length === 0 ? (
          <div className="text-sm text-muted-foreground">{t("roles.noPermissions")}</div>
        ) : (
          allPermissions.map((permission) => (
            <label key={permission} className="flex cursor-pointer items-center gap-2 text-sm">
              <Checkbox checked={selectedSet.has(permission)} onChange={() => toggle(permission)} />
              <span className="font-mono text-xs">{permission}</span>
            </label>
          ))
        )}
      </div>
    </div>
  )
}
