<!-- 用户管理页面(2026-07-03 重写)—— 字段对齐 mcp_user 表,操作换成编辑/启用禁用/TOTP/重置密码。账号不允许删除,管理员不允许被禁用。 -->
<template>
  <div class="user-page art-full-height">
    <UserSearch v-model="searchForm" @search="handleSearch" @reset="handleReset" />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElButton type="primary" @click="showDialog('add')" v-ripple>新增用户</ElButton>
          </ElSpace>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />

      <!-- 创建 / 编辑 弹窗 -->
      <UserDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :user-data="currentUserData"
        @submit="handleDialogSubmit"
      />

      <!-- 重置密码弹窗 -->
      <ElDialog v-model="resetDialogVisible" title="重置密码" width="420px" align-center>
        <ElForm ref="resetFormRef" :model="resetForm" :rules="resetRules">
          <ElFormItem label="新密码" prop="newPassword">
            <ElInput v-model="resetForm.newPassword" type="password" show-password placeholder="至少 8 位" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="resetDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="resetSubmitting" @click="handleResetPasswordSubmit">提交</ElButton>
        </template>
      </ElDialog>

      <!-- TOTP 管理弹窗 -->
      <UserTotpDialog
        v-model:visible="totpDialogVisible"
        :user-id="totpTargetId"
        @updated="refreshData"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import {
    ElMessage,
    ElMessageBox,
    ElTag,
    ElButton,
    type FormInstance,
    type FormRules
  } from 'element-plus'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchUserPage,
    resetUserPassword,
    updateUser,
    type ImawxUserListItem,
    type ImawxUserQuery
  } from '@/api/sys/account'
  import UserSearch from './modules/user-search.vue'
  import UserDialog from './modules/user-dialog.vue'
  import UserTotpDialog from './modules/user-totp-dialog.vue'

  defineOptions({ name: 'User' })

  type DialogType = 'add' | 'edit'

  // 搜索表单
  const searchForm = ref<ImawxUserQuery>({
    keyword: undefined,
    status: undefined,
    pageNum: 1,
    pageSize: 20
  })

  // 弹窗状态
  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const currentUserData = ref<Partial<ImawxUserListItem>>({})

  // 重置密码弹窗
  const resetDialogVisible = ref(false)
  const resetSubmitting = ref(false)
  const resetFormRef = ref<FormInstance>()
  const resetForm = reactive({ newPassword: '' })
  const resetRules: FormRules = {
    newPassword: [
      { required: true, message: '请输入新密码', trigger: 'blur' },
      { min: 8, max: 64, message: '密码长度 8-64 位', trigger: 'blur' }
    ]
  }
  const resetTargetId = ref<string>('')

  // TOTP 弹窗
  const totpDialogVisible = ref(false)
  const totpTargetId = ref<string | number>('')

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchUserPage,
      apiParams: { pageNum: 1, pageSize: 20, ...searchForm.value },
      columnsFactory: () => [
        { type: 'index', width: 60, label: '序号' },
        {
          prop: 'displayName',
          label: '显示名',
          minWidth: 140,
          formatter: (row: ImawxUserListItem) => {
            return h('div', { class: 'user-cell' }, [
              h('strong', row.displayName),
              // 2026-07-03 fix: 用导入的 ElTag 组件而不是字符串 'ElTag',否则 h() 在
              // script setup 里 resolve 不到 element-plus 组件(只在 template 自动解析),
              // 会渲染成空的 <eltag> 原生标签。
              row.isAdmin && h(ElTag, { type: 'danger', size: 'small', effect: 'plain' }, () => '管理员')
            ])
          }
        },
        { prop: 'username', label: '用户名', minWidth: 140 },
        { prop: 'email', label: '邮箱', minWidth: 200 },
        {
          prop: 'totpEnabled',
          label: '2FA',
          width: 80,
          formatter: (row: ImawxUserListItem) => h(
            ElTag,
            { type: row.totpEnabled ? 'success' : 'info', size: 'small' },
            () => (row.totpEnabled ? '已启用' : '未启用')
          )
        },
        {
          prop: 'status',
          label: '状态',
          width: 90,
          formatter: (row: ImawxUserListItem) => h(
            ElTag,
            { type: row.status === 1 ? 'success' : 'danger', size: 'small' },
            () => (row.status === 1 ? '启用' : '禁用')
          )
        },
        { prop: 'lastLoginAt', label: '最后登录', minWidth: 160 },
        {
          prop: 'operation',
          label: '操作',
          width: 260,
          fixed: 'right',
          formatter: (row: ImawxUserListItem) => h('div', { class: 'op-cell' }, [
            h(ElButton, { type: 'primary', link: true, onClick: () => showDialog('edit', row) }, () => '编辑'),
            h(ElButton, { type: 'warning', link: true, onClick: () => showTotpDialog(row) }, () => 'TOTP'),
            h(ElButton, { type: 'info', link: true, onClick: () => showResetPassword(row) }, () => '重置密码'),
            // 管理员行不显示启用/禁用按钮(不允许改)
            !row.isAdmin && h(
              ElButton,
              {
                type: row.status === 1 ? 'danger' : 'success',
                link: true,
                onClick: () => toggleStatus(row)
              },
              () => (row.status === 1 ? '禁用' : '启用')
            )
          ])
        }
      ]
    }
  })

  // 搜索
  const handleSearch = (params: ImawxUserQuery) => {
    replaceSearchParams({ ...params, pageNum: 1, pageSize: 20 })
    getData()
  }
  const handleReset = () => {
    searchForm.value = { keyword: undefined, status: undefined, pageNum: 1, pageSize: 20 }
    replaceSearchParams(searchForm.value)
    getData()
  }

  // 创建 / 编辑
  const showDialog = (type: DialogType, row?: ImawxUserListItem) => {
    dialogType.value = type
    currentUserData.value = row || {}
    dialogVisible.value = true
  }
  const handleDialogSubmit = () => {
    refreshData()
  }

  // 启用 / 禁用
  const toggleStatus = async (row: ImawxUserListItem) => {
    const action = row.status === 1 ? '禁用' : '启用'
    try {
      await ElMessageBox.confirm(
        `${action}账号 ${row.displayName}(${row.email})?${row.status === 1 ? '\n禁用后该账号登录 + 所有 API token 立即失效。' : ''}`,
        `${action}账号`,
        { type: row.status === 1 ? 'warning' : 'info' }
      )
    } catch {
      return
    }
    try {
      await updateUser(row.id, { status: row.status === 1 ? 0 : 1 })
      ElMessage.success(`${action}成功`)
      refreshData()
    } catch (e: any) {
      ElMessage.error(e?.message || `${action}失败`)
    }
  }

  // 重置密码
  const showResetPassword = (row: ImawxUserListItem) => {
    resetTargetId.value = row.id
    resetForm.newPassword = ''
    resetDialogVisible.value = true
  }
  const handleResetPasswordSubmit = async () => {
    if (!resetFormRef.value) return
    await resetFormRef.value.validate(async (valid) => {
      if (!valid) return
      resetSubmitting.value = true
      try {
        await resetUserPassword(resetTargetId.value, resetForm.newPassword)
        ElMessage.success('重置成功,新密码已生效')
        resetDialogVisible.value = false
      } catch (e: any) {
        ElMessage.error(e?.message || '重置失败')
      } finally {
        resetSubmitting.value = false
      }
    })
  }

  // TOTP 管理
  const showTotpDialog = (row: ImawxUserListItem) => {
    totpTargetId.value = row.id
    totpDialogVisible.value = true
  }
</script>

<style scoped>
  .user-cell,
  .op-cell {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .op-cell {
    flex-wrap: wrap;
  }
</style>
