<template>
  <ArtSearchBar
    ref="searchBarRef"
    v-model="formData"
    :items="formItems"
    @reset="handleReset"
    @search="handleSearch"
  />
</template>

<script setup lang="ts">
  import type { ImawxUserQuery } from '@/api/sys/account'

  interface Props {
    modelValue: ImawxUserQuery
  }
  interface Emits {
    (e: 'update:modelValue', value: ImawxUserQuery): void
    (e: 'search', params: ImawxUserQuery): void
    (e: 'reset'): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const formData = computed({
    get: () => props.modelValue,
    set: (val) => emit('update:modelValue', val)
  })

  const formItems = computed(() => [
    {
      key: 'keyword',
      prop: 'keyword',
      label: '关键字',
      type: 'input',
      placeholder: '用户名 / 邮箱 / 显示名',
      width: '220px'
    },
    {
      key: 'status',
      prop: 'status',
      label: '状态',
      type: 'select',
      width: '140px',
      options: [
        { label: '全部', value: undefined },
        { label: '启用', value: 1 },
        { label: '禁用', value: 0 }
      ]
    }
  ])

  const handleSearch = (params: ImawxUserQuery) => {
    emit('search', params)
  }
  const handleReset = () => {
    emit('reset')
  }
</script>
