<!--
  新增 / 编辑外部 MCP Server 对话框

  复用：list 页面和 detail 页面共用（detail 页通过 props.record 传当前记录）

  字段：
  - serverName (必填，唯一)
  - transportType (必填，STDIO / SSE / HTTP)
  - endpoint (必填，URL 或命令) — 输入时 watch 触发自动推荐 transportType
  - authToken (可选，SSE/HTTP 用，编辑时留空表示不修改)
  - extraConfig (STDIO 必填，JSON {args:[...], env:{...}})
  - remark (可选)

  设计要点（2026-06-28 反馈）：
  - 保留 3 选 1 radio（用户原文：「保留 radio 但加自动推荐」）
  - 用户输入 endpoint 时，自动按 URL 形态推荐 transportType：
    * URL 含 "/sse" 或 "text/event-stream" → SSE
    * URL 是 http/https 但不带 SSE 特征 → HTTP
    * 其他（命令路径）→ STDIO
  - 自动推荐只在「新填/手动修改」时生效；用户已显式选过就不再覆盖（避免边输入边跳）
  - 不要在字段下面加 .el-form-item__hint 长段落 —— 改用 icon + ElTooltip 弹解释（用户反馈）
-->
<template>
  <ElDialog
    :model-value="visible"
    :title="isEdit ? '编辑 MCP 服务' : '新增 MCP 服务'"
    width="560px"
    top="8vh"
    class="imawx-dialog-fixed imawx-dialog-fixed--compact"
    :close-on-click-modal="false"
    @update:model-value="(v) => emit('update:visible', v)"
    @open="onOpen"
  >
    <ElForm ref="formRef" :model="form" :rules="rules" label-width="120px" label-position="right">
      <ElFormItem label="服务名" prop="serverName">
        <ElInput
          v-model="form.serverName"
          placeholder="例：我的 GitHub MCP"
          maxlength="128"
          show-word-limit
        />
      </ElFormItem>

      <ElFormItem label="接入来源">
        <ElSelect
          v-model="selectedAccessValue"
          :disabled="isEdit"
          filterable
          style="width: 100%"
          :loading="templateLoading"
          placeholder="选择远程协议或内部 MCP 类型"
          @change="handleAccessPathChange"
        >
          <ElOption
            v-for="item in accessSelectOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <div v-if="selectedTemplate?.securityNote" class="imawx-mcp-proxy-form__template-note">
          {{ selectedTemplate.securityNote }}
        </div>
      </ElFormItem>

      <template v-if="isDbMode">
        <ElFormItem label="数据库类型" prop="dbType">
          <ElSelect v-model="dbForm.dbType" placeholder="选择" style="width: 100%">
            <ElOption
              v-for="t in dbTypeOptions"
              :key="String(t.value)"
              :label="dbTypeOptionLabel(t)"
              :value="String(t.value)"
            />
          </ElSelect>
        </ElFormItem>

        <ElFormItem label="Host" prop="dbHost">
          <ElInput v-model.trim="dbForm.host" placeholder="例：127.0.0.1 / mysql.internal" maxlength="255" />
        </ElFormItem>

        <ElFormItem label="端口" prop="dbPort">
          <ElInputNumber v-model="dbForm.port" :min="1" :max="65535" style="width: 100%" />
        </ElFormItem>

        <ElFormItem label="数据库/Schema" prop="dbName">
          <ElInput v-model.trim="dbForm.databaseName" placeholder="例：imawx / public / HR" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="用户名" prop="dbUsername">
          <ElInput v-model.trim="dbForm.username" placeholder="数据库账号" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="密码" prop="dbPassword">
          <ElInput
            v-model="dbForm.password"
            type="password"
            show-password
            placeholder="数据库明文密码（RSA-OAEP 加密后落库）"
            maxlength="512"
          />
        </ElFormItem>

      </template>

      <ElFormItem v-if="!isDbMode && !isNoSqlMode && !isOpenApiMode && !isSshMode && !isDroneMode && (isExternalMode || isBuiltinMode)" :label="endpointLabel" prop="endpoint">
        <ElInput v-model="form.endpoint" :placeholder="endpointPlaceholder" maxlength="512" />
      </ElFormItem>

      <template v-if="isAliyunMode">
        <ElFormItem label="AccessKeyId" prop="aliyunAccessKeyId">
          <ElInput v-model.trim="aliyunForm.accessKeyId" placeholder="阿里云 AccessKeyId" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="AccessKeySecret" prop="authToken">
          <ElInput
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : '阿里云 AccessKeySecret（RSA-OAEP 加密后落库）'"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem v-if="isAliyunDnsMode" label="限定域名">
          <ElInput
            v-model.trim="aliyunForm.domainName"
            placeholder="可空；填写后该 MCP 只能操作这个域名，不填则按 AK 权限拉取域名"
            maxlength="128"
          />
        </ElFormItem>

        <ElFormItem v-if="isAliyunOssMode" label="限定 Bucket">
          <ElInput
            v-model.trim="aliyunForm.bucketName"
            placeholder="可空；填写后该 MCP 只能操作这个 bucket，不填则按 AK 权限拉取 bucket"
            maxlength="128"
          />
        </ElFormItem>
      </template>

      <template v-if="isRedisMode">
        <ElFormItem label="Host" prop="redisHost">
          <ElInput v-model.trim="redisForm.host" placeholder="例：127.0.0.1 / redis.internal" maxlength="255" />
        </ElFormItem>

        <ElFormItem label="端口" prop="redisPort">
          <ElInputNumber v-model="redisForm.port" :min="1" :max="65535" style="width: 100%" />
        </ElFormItem>

        <ElFormItem label="用户名">
          <ElInput v-model.trim="redisForm.username" placeholder="可空；Redis ACL 用户名" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="密码">
          <ElInput
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : 'Redis 密码（RSA-OAEP 加密后落库）'"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="TLS">
          <ElSwitch v-model="redisForm.tls" />
        </ElFormItem>

        <ElFormItem label="Key Prefix">
          <ElInput
            v-model.trim="redisForm.keyPrefix"
            placeholder="可空；填写后由内部 Redis MCP 服务限制 key 前缀"
            maxlength="128"
          />
        </ElFormItem>

        <ElFormItem label="默认 DB">
          <ElInputNumber v-model="redisForm.database" :min="0" :max="15" style="width: 100%" />
        </ElFormItem>

        <ElFormItem label="DB 白名单">
          <ElInput
            v-model.trim="redisForm.databases"
            placeholder="可空表示不限制；支持多个，例：0,1,2 或每行一个"
            maxlength="128"
          />
        </ElFormItem>

        <ElFormItem label="只读模式">
          <ElSwitch v-model="redisForm.readOnly" />
        </ElFormItem>

        <ElFormItem label="超时秒数">
          <ElInputNumber v-model="redisForm.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
        </ElFormItem>
      </template>

      <template v-if="isMongoMode">
        <ElFormItem label="Host" prop="mongoHost">
          <ElInput v-model.trim="mongoForm.host" placeholder="例：mongo.internal / 127.0.0.1" maxlength="255" />
        </ElFormItem>

        <ElFormItem label="端口" prop="mongoPort">
          <ElInputNumber v-model="mongoForm.port" :min="1" :max="65535" style="width: 100%" />
        </ElFormItem>

        <ElFormItem label="数据库" prop="mongoDatabase">
          <ElInput v-model.trim="mongoForm.database" placeholder="MongoDB database" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="用户名">
          <ElInput v-model.trim="mongoForm.username" placeholder="可空；MongoDB 用户名" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="密码">
          <ElInput
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : 'MongoDB 密码（RSA-OAEP 加密后落库）'"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="Auth DB">
          <ElInput v-model.trim="mongoForm.authDatabase" placeholder="默认 admin" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="TLS">
          <ElSwitch v-model="mongoForm.tls" />
        </ElFormItem>

        <ElFormItem label="Collection Prefix">
          <ElInput v-model.trim="mongoForm.collectionPrefix" placeholder="可空；限制可操作 collection 前缀" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="超时秒数">
          <ElInputNumber v-model="mongoForm.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
        </ElFormItem>
      </template>

      <template v-if="isElasticsearchMode">
        <ElFormItem label="Host" prop="esHost">
          <ElInput v-model.trim="esForm.host" placeholder="例：es.internal / 127.0.0.1" maxlength="255" />
        </ElFormItem>

        <ElFormItem label="端口" prop="esPort">
          <ElInputNumber v-model="esForm.port" :min="1" :max="65535" style="width: 100%" />
        </ElFormItem>

        <ElFormItem label="用户名">
          <ElInput v-model.trim="esForm.username" placeholder="可空；填了则使用 Basic Auth" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="密码/Token">
          <ElInput
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : 'ES 密码或 Bearer Token（RSA-OAEP 加密后落库）'"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="HTTPS">
          <ElSwitch v-model="esForm.tls" />
        </ElFormItem>

        <ElFormItem label="Index Prefix">
          <ElInput v-model.trim="esForm.indexPrefix" placeholder="可空；限制可操作 index 前缀" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="超时秒数">
          <ElInputNumber v-model="esForm.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
        </ElFormItem>
      </template>

      <template v-if="isOpenApiMode">
        <ElFormItem label="文档 URL" prop="openApiSpecUrl">
          <ElInput
            v-model.trim="openApiForm.specUrl"
            placeholder="例：https://api.example.com/v3/api-docs 或 /swagger.json"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="Base URL" prop="openApiBaseUrl">
          <ElInput
            v-model.trim="openApiForm.baseUrl"
            placeholder="例：https://api.example.com；留空则从文档推断"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="认证方式">
          <ElSelect v-model="openApiForm.authType" style="width: 100%">
            <ElOption label="无认证" value="NONE" />
            <ElOption label="Basic 用户名/密码" value="BASIC" />
            <ElOption label="Bearer Token" value="BEARER" />
            <ElOption label="API Key Header" value="API_KEY_HEADER" />
          </ElSelect>
        </ElFormItem>

        <ElFormItem v-if="openApiForm.authType === 'BASIC'" label="用户名">
          <ElInput v-model.trim="openApiForm.basicUsername" placeholder="Basic Auth 用户名" maxlength="128" />
        </ElFormItem>

        <ElFormItem v-if="openApiForm.authType === 'API_KEY_HEADER'" label="Header 名">
          <ElInput v-model.trim="openApiForm.apiKeyHeader" placeholder="例：X-API-Key" maxlength="128" />
        </ElFormItem>

        <ElFormItem v-if="openApiForm.authType !== 'NONE'" label="密钥/密码" prop="authToken">
          <ElInput
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : '密码、Bearer Token 或 API Key（RSA-OAEP 加密后落库）'"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="允许 Method">
          <ElCheckboxGroup v-model="openApiForm.allowedMethods">
            <ElCheckboxButton label="get">GET</ElCheckboxButton>
            <ElCheckboxButton label="post">POST</ElCheckboxButton>
            <ElCheckboxButton label="put">PUT</ElCheckboxButton>
            <ElCheckboxButton label="patch">PATCH</ElCheckboxButton>
            <ElCheckboxButton label="delete">DELETE</ElCheckboxButton>
          </ElCheckboxGroup>
        </ElFormItem>

        <ElFormItem label="路径前缀">
          <ElInput v-model.trim="openApiForm.pathPrefix" placeholder="可空；例：/api/v1" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="白名单">
          <ElInput
            v-model="openApiForm.operationAllowlist"
            type="textarea"
            :rows="2"
            placeholder="可空；operationId/toolName，逗号或换行分隔。生产建议配置"
            maxlength="4096"
          />
        </ElFormItem>

        <ElFormItem label="黑名单">
          <ElInput
            v-model="openApiForm.operationDenylist"
            type="textarea"
            :rows="2"
            placeholder="可空；operationId/toolName，逗号或换行分隔"
            maxlength="4096"
          />
        </ElFormItem>

        <ElFormItem label="文档也鉴权">
          <ElSwitch v-model="openApiForm.authForSpec" />
        </ElFormItem>

        <ElFormItem label="文档缓存秒数">
          <ElInputNumber v-model="openApiForm.specCacheSeconds" :min="0" :max="3600" style="width: 100%" />
        </ElFormItem>
      </template>

      <template v-if="isSshMode">
        <ElFormItem label="Host" prop="sshHost">
          <ElInput v-model.trim="sshForm.host" placeholder="例：server.internal / 192.168.1.10" maxlength="255" />
        </ElFormItem>

        <ElFormItem label="端口" prop="sshPort">
          <ElInputNumber v-model="sshForm.port" :min="1" :max="65535" style="width: 100%" />
        </ElFormItem>

        <ElFormItem label="用户名" prop="sshUsername">
          <ElInput v-model.trim="sshForm.username" placeholder="SSH 用户名" maxlength="128" />
        </ElFormItem>

        <ElFormItem label="认证方式">
          <ElRadioGroup v-model="sshForm.authType">
            <ElRadioButton label="PASSWORD">密码</ElRadioButton>
            <ElRadioButton label="PRIVATE_KEY">私钥</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>

        <ElFormItem :label="sshForm.authType === 'PRIVATE_KEY' ? '私钥' : '密码'" prop="authToken">
          <ElInput
            v-if="sshForm.authType === 'PRIVATE_KEY'"
            v-model="form.authToken"
            type="textarea"
            :rows="8"
            :placeholder="isEdit ? '留空表示不修改' : '粘贴 SSH 私钥 PEM（加密后落库）'"
            maxlength="12000"
          />
          <ElInput
            v-else
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : 'SSH 密码（加密后落库）'"
            maxlength="1024"
          />
        </ElFormItem>

        <ElFormItem label="允许命令">
          <ElInput
            v-model="sshForm.allowedCommandsText"
            type="textarea"
            :rows="5"
            placeholder="可空；为空表示允许执行全部命令。一行一条时必须完整匹配。例如：&#10;df -h&#10;systemctl status nginx"
            maxlength="4096"
          />
        </ElFormItem>

        <ElFormItem label="超时秒数">
          <ElInputNumber v-model="sshForm.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
        </ElFormItem>
      </template>

      <template v-if="isDroneMode">
        <ElFormItem label="Drone Server" prop="droneBaseUrl">
          <ElInput
            v-model.trim="droneForm.baseUrl"
            placeholder="例：https://drone.example.com 或 http://drone.internal:8080"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="仓库路径" prop="droneRepo">
          <ElInput
            v-model.trim="droneForm.repo"
            placeholder="例：my-org/my-app"
            maxlength="256"
          />
        </ElFormItem>

        <ElFormItem label="Personal Access Token" prop="authToken">
          <ElInput
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : 'Drone Personal Access Token（RSA-OAEP 加密后落库）'"
            maxlength="1024"
          />
        </ElFormItem>

        <ElFormItem label="跳过 TLS 校验">
          <ElSwitch v-model="droneForm.insecure" />
          <span class="text-xs text-gray-500 ml-2">自签证书场景打开,生产保持关闭</span>
        </ElFormItem>

        <ElFormItem label="超时秒数">
          <ElInputNumber v-model="droneForm.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
        </ElFormItem>
      </template>

      <template v-if="isKvMode">
        <ElFormItem label="Host" prop="kvHost">
          <ElInput v-model.trim="kvForm.host" placeholder="例：kv.internal / 127.0.0.1" maxlength="255" />
        </ElFormItem>

        <ElFormItem label="端口" prop="kvPort">
          <ElInputNumber v-model="kvForm.port" :min="1" :max="65535" style="width: 100%" />
        </ElFormItem>

        <ElFormItem label="协议">
          <ElSwitch
            v-model="kvForm.tls"
            active-text="HTTPS"
            inactive-text="HTTP"
            inline-prompt
          />
        </ElFormItem>

        <ElFormItem label="Base Path">
          <ElInput
            v-model.trim="kvForm.basePath"
            placeholder="例：/api/kv；留空则使用 /kv"
            maxlength="128"
          />
        </ElFormItem>

        <ElFormItem label="Namespace" prop="kvNamespace">
          <ElInput
            v-model.trim="kvForm.namespace"
            placeholder="必填；例：prod / ops / billing"
            maxlength="128"
          />
        </ElFormItem>

        <ElFormItem label="Key Prefix" prop="kvKeyPrefix">
          <ElInput
            v-model.trim="kvForm.keyPrefix"
            placeholder="必填；例：mcp:prod:，用于限制可操作 key 范围"
            maxlength="128"
          />
        </ElFormItem>

        <ElFormItem label="鉴权 Token">
          <ElInput
            v-model="form.authToken"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改' : '可选；调用 KV HTTP API 时作为 Bearer Token 转发'"
            maxlength="512"
          />
        </ElFormItem>

        <ElFormItem label="超时秒数">
          <ElInputNumber v-model="kvForm.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
        </ElFormItem>
      </template>

      <ElFormItem
        v-if="!isDbMode && (isExternalMode || isBuiltinMode) && !isAliyunMode && !isNoSqlMode && !isKvMode && !isOpenApiMode && !isSshMode && !isDroneMode && form.transportType !== 'STDIO'"
        prop="authToken"
      >
        <template #label>
          <span class="imawx-mcp-proxy-form__label">
            鉴权 Token
            <ElTooltip
              placement="top"
              :show-after="200"
              content="存储时 RSA-OAEP 加密，前端不再回显原文"
            >
              <ElIcon class="imawx-mcp-proxy-form__icon"><QuestionFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <ElInput
          v-model="form.authToken"
          type="password"
          show-password
          :placeholder="isEdit ? '留空表示不修改' : '可选，HTTP/SSE 鉴权用'"
          maxlength="512"
        />
      </ElFormItem>

      <ElFormItem v-if="!isDbMode && isExternalMode && form.transportType === 'STDIO'" label="扩展配置" prop="extraConfig">
        <template #label>
          <span class="imawx-mcp-proxy-form__label">
            扩展配置
            <ElTooltip
              placement="top"
              :show-after="200"
              :raw-content="true"
              content='STDIO 必填，JSON 格式 <code>{ "args": [...], "env": {...} }</code>'
            >
              <ElIcon class="imawx-mcp-proxy-form__icon"><QuestionFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <ElInput
          v-model="form.extraConfig"
          type="textarea"
          :rows="4"
          :placeholder="`例：\n${STDIO_PLACEHOLDER}`"
          maxlength="4096"
        />
      </ElFormItem>

      <!-- 2026-07-01 加:用户标签输入。最多 20 个,每个 ≤32 字符。
           用 closable ElTag + Input 的标准 pattern:回车添加,点 × 删除,
           自动去重 + trim + 空字符串过滤。tags 是响应式数组,form 直接绑。 -->
      <ElFormItem prop="tags">
        <template #label>
          <span class="imawx-mcp-proxy-form__label">
            标签
            <ElTooltip
              placement="top"
              :show-after="200"
              content="手动添加分类标签（如 生产 / 测试 / 团队-A），输入后按回车确认；最多 20 个，单个 ≤32 字符"
            >
              <ElIcon class="imawx-mcp-proxy-form__icon"><QuestionFilled /></ElIcon>
            </ElTooltip>
          </span>
        </template>
        <div class="imawx-mcp-proxy-form__tags">
          <ElTag
            v-for="tag in form.tags"
            :key="tag"
            closable
            :type="tagColor(tag)"
            effect="light"
            :disable-transitions="true"
            @close="removeTag(tag)"
          >
            {{ tag }}
          </ElTag>
          <ElInput
            v-if="tagInputVisible"
            ref="tagInputRef"
            v-model="tagInputValue"
            class="imawx-mcp-proxy-form__tag-input"
            size="small"
            :maxlength="32"
            placeholder="新标签 + 回车"
            @keyup.enter="confirmTag"
            @blur="confirmTag"
          />
          <ElButton
            v-else
            size="small"
            text
            class="imawx-mcp-proxy-form__tag-add"
            @click="showTagInput"
          >
            <template #icon><ArtSvgIcon icon="ri:add-line" /></template>
            添加标签
          </ElButton>
        </div>
      </ElFormItem>

      <ElFormItem label="备注">
        <ElInput
          v-model="form.remark"
          type="textarea"
          :rows="2"
          placeholder="例：内部 GitHub MCP，给研发组用；或上下游注意事项"
          maxlength="512"
          show-word-limit
        />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <div class="imawx-mcp-proxy-form__footer">
        <!-- 测试状态指示:通过 → 绿色 chip;失败 → 红色文字;未测试 → 灰色 hint -->
        <span
          v-if="testState === 'passed'"
          class="imawx-mcp-proxy-form__status imawx-mcp-proxy-form__status--ok"
        >
          <ElIcon><CircleCheckFilled /></ElIcon>
          测试通过（{{ testToolCount }} 个 Tool）
        </span>
        <span
          v-else-if="testState === 'failed'"
          class="imawx-mcp-proxy-form__status imawx-mcp-proxy-form__status--err"
        >
          <ElIcon><CircleCloseFilled /></ElIcon>
          测试失败，请修改后重试
        </span>
        <span v-else-if="isEdit && !connectionChanged" class="imawx-mcp-proxy-form__status imawx-mcp-proxy-form__status--hint">
          连接配置未变更，可直接保存
        </span>
        <span v-else class="imawx-mcp-proxy-form__status imawx-mcp-proxy-form__status--hint">
          {{ isEdit ? '连接配置已变更，测试通过后才能保存' : '填好配置后点「测试」，通过后才能保存' }}
        </span>
        <span class="imawx-mcp-proxy-form__spacer" />
        <ElButton :disabled="saving || testing" @click="emit('update:visible', false)">取消</ElButton>
        <ElButton
          :loading="testing"
          :disabled="!canTest"
          @click="handleTest"
        >
          测试
        </ElButton>
        <ElButton
          type="primary"
          :loading="saving"
          :disabled="!canSave || saving"
          @click="handleSubmit"
        >
          保存
        </ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import {
    CircleCheckFilled,
    CircleCloseFilled,
    QuestionFilled
  } from '@element-plus/icons-vue'
  import { ElInput, ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import {
    createMcpProxySys,
    fetchMcpProxyTemplatesSys,
    updateMcpProxySys,
    validateMcpProxySys,
    type ImawxMcpProxy,
    type ImawxMcpProxyPayload,
    type ImawxMcpProxyTemplate,
    type ImawxMcpTransport
  } from '@/api/sys/mcp-proxy'
  import { useConstants } from '@/composables/useConstants'
  import { tagColor } from '@/composables/useTagColors'

  type ImawxDbType = 'MYSQL' | 'POSTGRESQL' | 'ORACLE' | 'SQLSERVER'

  interface Props {
    visible: boolean
    record: ImawxMcpProxy | null
  }
  const props = defineProps<Props>()
  const emit = defineEmits<{
    'update:visible': [boolean]
    saved: []
  }>()

  defineOptions({ name: 'ImawxMcpProxyFormDialog' })

  /**
   * 协议列表从 useConstants 缓存读（模块级 ref），后端新增协议不用改前端代码。
   * App.vue 启动时已 loadConstants,这里直接读,不用 onMounted 再 fetch。
   * 缓存未加载完成时(getOptions 返 [])RadioGroup 不渲染任何 button,等 App.vue 加载完自动重算。
   */
  const { getOptions } = useConstants()
  const fallbackDbTypeOptions = [
    { value: 'MYSQL', label: 'MySQL', ext: { defaultPort: 3306 } },
    { value: 'POSTGRESQL', label: 'PostgreSQL', ext: { defaultPort: 5432 } },
    { value: 'ORACLE', label: 'Oracle', ext: { defaultPort: 1521 } },
    { value: 'SQLSERVER', label: 'SQL Server', ext: { defaultPort: 1433 } }
  ]
  const fallbackProtocolOptions = [
    { value: 'HTTP' as ImawxMcpTransport, label: 'HTTP' },
    { value: 'SSE' as ImawxMcpTransport, label: 'SSE' },
    { value: 'STDIO' as ImawxMcpTransport, label: 'STDIO' }
  ]
  const dbTypeOptions = computed(() => {
    const options = getOptions('dbType')
    return options.length ? options : fallbackDbTypeOptions
  })
  /**
   * 协议列表 —— 2026-07-06 重构：AUTO 选项已移除。
   *
   * <p>外部 MCP 接入必须明确选 HTTP / SSE / STDIO transportType(从后端
   * {@code useConstants('protocol')} 字典取字典项)，不再"自动探测"。
   */
  const protocolOptions = computed(() =>
    getOptions('protocol').length
      ? getOptions('protocol')
          .filter((o) => o.value !== undefined && o.value !== null && String(o.value).trim() !== '')
          .map((o) => ({ value: o.value as ImawxMcpTransport, label: o.label || o.desc || String(o.value) }))
      : fallbackProtocolOptions
  )

  const STDIO_PLACEHOLDER = JSON.stringify(
    { args: ['server.js', '--port', '3000'], env: { LOG_LEVEL: 'info' } },
    null,
    2
  )

  const formRef = ref<FormInstance>()
  const saving = ref(false)
  const templates = ref<ImawxMcpProxyTemplate[]>([])
  const accessSource = ref<'EXTERNAL' | 'BUILTIN'>('EXTERNAL')
  /**
   * 2026-07-06 重构：默认 transportType 从 'AUTO' 改成 'HTTP' —— AUTO 已删除，
   * user 必须明确选 HTTP / SSE / STDIO（默认 HTTP 是最常见的）。
   */
  const accessPath = ref<string[]>(['EXTERNAL', 'HTTP'])
  const selectedAccessValue = ref('EXTERNAL_HTTP')
  const selectedTemplateKey = ref('')
  const templateLoading = ref(false)
  const fallbackTemplates: ImawxMcpProxyTemplate[] = [
    {
      key: 'EXTERNAL_MCP',
      name: '外部 MCP',
      description: '对接已经实现 MCP 协议的外部服务',
      transportType: 'HTTP',
      endpoint: '',
      providerType: 'HTTP_MCP',
      tags: ['外部 MCP']
    },
    {
      key: 'DB',
      name: '关系数据库',
      description: '内置 JDBC 数据库 MCP，支持 MySQL / PostgreSQL / Oracle / SQL Server',
      transportType: 'MYSQL',
      endpoint: 'jdbc:mysql://host:3306/dbname?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
      providerType: 'DB',
      tags: ['数据库', 'JDBC', '内置 MCP']
    },
    {
      key: 'ALIYUN_DNS_OPS',
      name: '阿里云域名 / DNS',
      description: '内置阿里云云解析 DNS SDK provider',
      transportType: 'ALIYUN_DNS',
      endpoint: 'alidns.cn-hangzhou.aliyuncs.com',
      providerType: 'ALIYUN_DNS',
      extraConfig: '{"accessKeyId":"","domainName":""}',
      tags: ['阿里云', '域名', 'DNS', 'CloudOps']
    },
    {
      key: 'ALIYUN_OSS',
      name: '阿里云 OSS',
      description: '内置阿里云 OSS SDK provider',
      transportType: 'ALIYUN_OSS',
      endpoint: 'https://oss-cn-hangzhou.aliyuncs.com',
      providerType: 'ALIYUN_OSS',
      extraConfig: '{"accessKeyId":"","bucketName":""}',
      tags: ['阿里云', 'OSS', '对象存储']
    },
    {
      key: 'OPENAPI',
      name: 'Swagger / OpenAPI',
      description: '把外部 Swagger/OpenAPI 文档转换成 MCP tools',
      transportType: 'OPENAPI',
      endpoint: 'https://api.example.com/v3/api-docs',
      providerType: 'OPENAPI',
      extraConfig: '{"specUrl":"https://api.example.com/v3/api-docs","baseUrl":"https://api.example.com","authType":"NONE","allowedMethods":["get"],"operationAllowlist":"","operationDenylist":"","pathPrefix":"","timeoutSeconds":30,"authForSpec":true,"specCacheSeconds":60}',
      tags: ['OpenAPI', 'Swagger', 'HTTP', '内置 MCP']
    },
    {
      key: 'REDIS',
      name: 'Redis',
      description: '内置 Redis 动态连接 MCP provider',
      transportType: 'REDIS',
      endpoint: 'redis://host:6379/0',
      providerType: 'REDIS',
      extraConfig: '{"host":"","port":6379,"username":"","tls":false,"database":0,"databases":[],"keyPrefix":"","readOnly":false,"timeoutSeconds":30}',
      tags: ['Redis', 'NoSQL', '数据库']
    },
    {
      key: 'MONGODB',
      name: 'MongoDB',
      description: '内置 MongoDB NoSQL 数据库 MCP provider',
      transportType: 'MONGODB',
      endpoint: 'mongodb://host:27017/database',
      providerType: 'MONGODB',
      extraConfig: '{"host":"","port":27017,"database":"","username":"","authDatabase":"admin","tls":false,"collectionPrefix":"","timeoutSeconds":30}',
      tags: ['MongoDB', 'NoSQL', '数据库']
    },
    {
      key: 'ELASTICSEARCH',
      name: 'Elasticsearch',
      description: '内置 Elasticsearch NoSQL 搜索数据库 MCP provider',
      transportType: 'ELASTICSEARCH',
      endpoint: 'http://host:9200',
      providerType: 'ELASTICSEARCH',
      extraConfig: '{"host":"","port":9200,"username":"","tls":false,"indexPrefix":"","timeoutSeconds":30}',
      tags: ['Elasticsearch', 'NoSQL', '搜索数据库']
    },
    {
      key: 'SSH',
      name: 'SSH',
      description: '内置 SSH MCP provider，可配置命令范围',
      transportType: 'SSH',
      endpoint: 'ssh://host:22',
      providerType: 'SSH',
      extraConfig: '{"host":"","port":22,"username":"root","authType":"PASSWORD","allowedCommands":[],"timeoutSeconds":30}',
      tags: ['SSH', '主机运维', '内置 MCP']
    },
    {
      key: 'DRONE',
      name: 'Drone CI/CD',
      description: '内置 Drone CI/CD provider，按 build 查进度和日志',
      transportType: 'DRONE',
      endpoint: 'http://drone-server:8080',
      providerType: 'DRONE',
      extraConfig: '{"baseUrl":"","repo":"","insecure":false,"timeoutSeconds":30}',
      tags: ['Drone', 'CI/CD', '内置 MCP']
    }
  ]
  const relationalDbOptions = computed(() => dbTypeOptions.value.map((item) => ({
    value: `DB_${String(item.value)}`,
    label: item.label || String(item.value)
  })))
  const templateOptions = computed(() => {
    const merged = new Map<string, ImawxMcpProxyTemplate>()
    for (const item of fallbackTemplates) merged.set(item.key, item)
    for (const item of templates.value) merged.set(item.key, item)
    return Array.from(merged.values())
  })
  const normalizedTemplates = computed(() => templateOptions.value.map((item) => ({
    ...item,
    providerType: providerTypeOf(item)
  })))
  const externalTemplate = computed(() => normalizedTemplates.value.find((t) => t.providerType === 'HTTP_MCP'))
  const internalTemplates = computed(() => normalizedTemplates.value.filter((t) => t.providerType !== 'HTTP_MCP'))
  const accessSelectOptions = computed(() => [
    ...protocolOptions.value.map((item) => ({
      value: `EXTERNAL_${item.value}`,
      label: `远程 / ${item.label}`
    })),
    ...relationalDbOptions.value.map((item) => ({
      value: item.value,
      label: `内部 / ${item.label}`
    })),
    { value: 'REDIS', label: '内部 / Redis' },
    { value: 'MONGODB', label: '内部 / MongoDB' },
    { value: 'ELASTICSEARCH', label: '内部 / Elasticsearch' },
    { value: 'ALIYUN_DNS_OPS', label: '内部 / 阿里云 DNS' },
    { value: 'ALIYUN_OSS', label: '内部 / 阿里云 OSS' },
    { value: 'SSH', label: '内部 / SSH' },
    { value: 'DRONE', label: '内部 / Drone' },
    { value: 'OPENAPI', label: '内部 / Swagger API' }
  ])
  const selectedTemplate = computed(() => normalizedTemplates.value.find((t) => t.key === selectedTemplateKey.value))
  const isEdit = computed(() => !!props.record)
  const selectedProviderType = computed(() => selectedTemplate.value?.providerType ?? providerTypeOfKey(selectedTemplateKey.value))
  const isDbTransport = (value?: string) => ['MYSQL', 'POSTGRESQL', 'ORACLE', 'SQLSERVER'].includes(String(value ?? '').toUpperCase())
  const isDbMode = computed(() => accessSource.value === 'BUILTIN' && (selectedProviderType.value === 'DB' || selectedTemplateKey.value === 'DB' || selectedTemplateKey.value.startsWith('DB_') || isDbTransport(form.transportType)))
  const isKvMode = computed(() => accessSource.value === 'BUILTIN' && (selectedProviderType.value === 'KV_DATABASE' || selectedTemplateKey.value === 'KV_DATABASE' || form.transportType === 'KV_DATABASE'))
  const isBuiltinMode = computed(() => isDbTransport(form.transportType) || ['ALIYUN_DNS', 'ALIYUN_OSS', 'REDIS', 'MONGODB', 'ELASTICSEARCH', 'OPENAPI', 'KV_DATABASE', 'SSH', 'DRONE'].includes(form.transportType))
  const isExternalMode = computed(() => isEdit.value ? (!isDbMode.value && !isBuiltinMode.value) : accessSource.value === 'EXTERNAL')
  const isAliyunDnsMode = computed(() => form.transportType === 'ALIYUN_DNS')
  const isAliyunOssMode = computed(() => form.transportType === 'ALIYUN_OSS')
  const isAliyunMode = computed(() => isAliyunDnsMode.value || isAliyunOssMode.value)
  const isRedisMode = computed(() => form.transportType === 'REDIS')
  const isMongoMode = computed(() => form.transportType === 'MONGODB')
  const isElasticsearchMode = computed(() => form.transportType === 'ELASTICSEARCH')
  const isNoSqlMode = computed(() => isRedisMode.value || isMongoMode.value || isElasticsearchMode.value)
  const isOpenApiMode = computed(() => form.transportType === 'OPENAPI')
  const isSshMode = computed(() => form.transportType === 'SSH')
  const isDroneMode = computed(() => form.transportType === 'DRONE')

  function providerTypeOf(item: ImawxMcpProxyTemplate): NonNullable<ImawxMcpProxyTemplate['providerType']> {
    if (item.providerType) return item.providerType
    return providerTypeOfKey(item.key)
  }

  function providerTypeOfKey(key?: string): NonNullable<ImawxMcpProxyTemplate['providerType']> {
    switch (key) {
      case 'DB':
        return 'DB'
      case 'DB_MYSQL':
      case 'DB_POSTGRESQL':
      case 'DB_ORACLE':
      case 'DB_SQLSERVER':
        return 'DB'
      case 'KV_DATABASE':
        return 'KV_DATABASE'
      case 'MONGODB':
        return 'MONGODB'
      case 'ELASTICSEARCH':
        return 'ELASTICSEARCH'
      case 'ALIYUN_DNS_OPS':
        return 'ALIYUN_DNS'
      case 'ALIYUN_OSS':
        return 'ALIYUN_OSS'
      case 'REDIS':
        return 'REDIS'
      case 'OPENAPI':
        return 'OPENAPI'
      case 'SSH':
        return 'SSH'
      case 'DRONE':
        return 'DRONE'
      default:
        return 'HTTP_MCP'
    }
  }
  // 测试状态机:'idle' | 'testing' | 'passed' | 'failed'
  // passed 才允许点保存；表单关键字段改动后自动重置回 'idle'（避免旧结果误导）
  const testState = ref<'idle' | 'testing' | 'passed' | 'failed'>('idle')
  const testing = computed(() => testState.value === 'testing')
  const testPassed = computed(() => testState.value === 'passed')
  const canSave = computed(() => {
    if (saving.value) return false
    if (!isEdit.value) return testPassed.value
    return testPassed.value || !connectionChanged.value
  })
  const testToolCount = ref(0)

  // 2026-07-06 重构：AUTO 探测状态机（probeState / probeResult / probing / probeOk /
  // probeMessage）已删除 —— 后端 /probe 端点和 AUTO transport 选项移除，前端不再需要
  // "自动识别 transport" 这条逻辑。表单默认 transportType 改为 HTTP，user 手动选即可。

  async function loadTemplates() {
    if (templates.value.length || templateLoading.value) return
    templateLoading.value = true
    try {
      templates.value = await fetchMcpProxyTemplatesSys()
    } catch {
      templates.value = fallbackTemplates
    } finally {
      templateLoading.value = false
    }
  }

  function handleAccessPathChange(value: unknown) {
    const selected = Array.isArray(value) ? String(value[value.length - 1] ?? '') : String(value ?? '')
    const externalMatch = selected.match(/^EXTERNAL_(HTTP|SSE|STDIO)$/)
    const source = externalMatch ? 'EXTERNAL' : 'BUILTIN'
    const leaf = externalMatch ? externalMatch[1] : selected
    accessSource.value = source
    selectedTemplateKey.value = source === 'EXTERNAL'
      ? (externalTemplate.value?.key ?? 'EXTERNAL_MCP')
      : (leaf.startsWith('DB_') ? 'DB' : leaf)
    selectedAccessValue.value = selected || 'EXTERNAL_HTTP'
    accessPath.value = source === 'EXTERNAL' ? ['EXTERNAL', leaf] : ['BUILTIN', leaf]
    form.authToken = ''
    form.extraConfig = ''
    testState.value = 'idle'
    testToolCount.value = 0
    if (source === 'EXTERNAL') {
      form.transportType = leaf as ImawxMcpTransport
      form.endpoint = ''
      applyDefaultTags(['外部 MCP'])
    } else {
      aliyunForm.accessKeyId = ''
      aliyunForm.domainName = ''
      aliyunForm.bucketName = ''
      redisForm.host = ''
      redisForm.port = 6379
      redisForm.username = ''
      redisForm.tls = false
      redisForm.keyPrefix = ''
      redisForm.databases = ''
      redisForm.timeoutSeconds = 30
      resetMongoForm()
      resetEsForm()
      resetKvForm()
      resetOpenApiForm()
      resetSshForm()
      resetDroneForm()
      resetDbForm()
      applyTemplate(leaf)
    }
    transportTypeManuallySet.value = false
    nextTick(() => formRef.value?.clearValidate())
  }

  function applyTemplate(key: unknown) {
    if (!key) return
    const rawKey = String(key)
    const dbTypeFromLeaf = dbTypeFromAccessLeaf(rawKey)
    const templateKey = dbTypeFromLeaf ? 'DB' : rawKey
    const tpl = normalizedTemplates.value.find((item) => item.key === templateKey)
    if (!tpl) return
    applyDefaultText('serverName', tpl.name)
    form.transportType = tpl.transportType
    form.endpoint = tpl.endpoint
    form.extraConfig = tpl.extraConfig ?? ''
    applyDefaultTags(tpl.tags ?? [])
    if (tpl.providerType === 'DB') {
      dbForm.dbType = dbTypeFromLeaf ?? 'MYSQL'
      dbForm.host = ''
      dbForm.port = defaultDbPort(dbForm.dbType)
      dbForm.databaseName = ''
      dbForm.username = ''
      dbForm.password = ''
      dbForm.schemaName = ''
      form.endpoint = ''
      form.transportType = dbForm.dbType
    }
    if (tpl.providerType === 'KV_DATABASE') {
      form.transportType = 'KV_DATABASE'
      form.endpoint = tpl.endpoint || 'http://kv.internal:8080/kv'
      form.extraConfig = tpl.extraConfig ?? '{"host":"","port":8080,"tls":false,"basePath":"/kv","namespace":"","keyPrefix":"","timeoutSeconds":30}'
      parseKvExtraConfig(form.extraConfig)
    }
    if (tpl.providerType === 'ALIYUN_DNS' || tpl.transportType === 'ALIYUN_DNS') {
      form.transportType = 'ALIYUN_DNS'
      form.endpoint = tpl.endpoint || 'alidns.cn-hangzhou.aliyuncs.com'
      parseAliyunExtraConfig(tpl.extraConfig)
    }
    if (tpl.providerType === 'ALIYUN_OSS' || tpl.transportType === 'ALIYUN_OSS') {
      form.transportType = 'ALIYUN_OSS'
      form.endpoint = tpl.endpoint || 'https://oss-cn-hangzhou.aliyuncs.com'
      parseAliyunExtraConfig(tpl.extraConfig)
    }
    if (tpl.providerType === 'REDIS' || tpl.transportType === 'REDIS') {
      form.transportType = 'REDIS'
      form.endpoint = 'redis://host:6379'
      parseRedisExtraConfig(tpl.extraConfig)
    }
    if (tpl.providerType === 'MONGODB' || tpl.transportType === 'MONGODB') {
      form.transportType = 'MONGODB'
      form.endpoint = 'mongodb://host:27017/database'
      parseMongoExtraConfig(tpl.extraConfig)
    }
    if (tpl.providerType === 'ELASTICSEARCH' || tpl.transportType === 'ELASTICSEARCH') {
      form.transportType = 'ELASTICSEARCH'
      form.endpoint = 'http://host:9200'
      parseEsExtraConfig(tpl.extraConfig)
    }
    if (tpl.providerType === 'OPENAPI' || tpl.transportType === 'OPENAPI') {
      form.transportType = 'OPENAPI'
      form.endpoint = tpl.endpoint || 'https://api.example.com/v3/api-docs'
      parseOpenApiExtraConfig(tpl.extraConfig)
    }
    if (tpl.providerType === 'SSH' || tpl.transportType === 'SSH') {
      form.transportType = 'SSH'
      form.endpoint = 'ssh://host:22'
      parseSshExtraConfig(tpl.extraConfig)
    }
    if (tpl.providerType === 'DRONE' || tpl.transportType === 'DRONE') {
      form.transportType = 'DRONE'
      form.endpoint = 'http://drone-server:8080'
      parseDroneExtraConfig(tpl.extraConfig)
    }
    applyDefaultText('remark', tpl.description)
    form.authToken = ''
    transportTypeManuallySet.value = true
    testState.value = 'idle'
    testToolCount.value = 0
    nextTick(() => formRef.value?.clearValidate())
  }

  function applyDefaultText(field: 'serverName' | 'remark', value?: string) {
    if (!value) return
    if (!form[field]?.trim()) {
      form[field] = value
    }
  }

  function applyDefaultTags(tags: string[]) {
    if (!form.tags?.length) {
      form.tags = [...tags]
    }
  }

  /**
   * 编辑时智能跳过测试 —— 原始字段快照(2026-07-01 加)。
   *
   * <p>用户原话："编辑的时候只要不修改链接，认证token，就不需要再次测试才能修改"。
   *
   * <p>策略：
   * <ul>
   *   <li>{@code originalEndpoint}: 编辑模式打开时记下 record.endpoint,watch 比较当前值</li>
   *   <li>{@code originalExtraConfig}: STDIO 才用,编辑模式记下 record.extraConfig</li>
   *   <li>authToken 编辑时永远是空字符串("留空表示不修改"语义) →
   *       「用户填了非空」= 改了;「仍是空」= 没改</li>
   *   <li>serverName / tags / remark 不影响连接 → 不 reset testState</li>
   * </ul>
   *
   * <p>onOpen 时如果三个关键字段都没变 → testState 直接 'passed',保存按钮立即可用。
   * 用户改了任何一个 → reset 回 'idle',必须点测试才能保存。
   */
  const originalEndpoint = ref('')
  const originalExtraConfig = ref('')
  const editingMode = ref(false)

  /**
   * 「测试」按钮可用条件：表单基础字段填完 + 当前没在测试中 + 当前没在保存中。
   * 用 computed 替代之前的 :disabled="..." 一长串判断。
   */
  const canTest = computed(() => {
    if (testing.value || saving.value) return false
    if (!form.serverName.trim()) return false
    if (isDbMode.value) {
      return !!dbForm.host.trim()
        && !!dbForm.port
        && !!dbForm.databaseName.trim()
        && !!dbForm.username.trim()
        && (isEdit.value || !!dbForm.password.trim())
    }
    if (isRedisMode.value) {
      return !!redisForm.host.trim() && !!redisForm.port && (isEdit.value || !!(form.authToken ?? '').trim())
    }
    if (isMongoMode.value) {
      return !!mongoForm.host.trim() && !!mongoForm.port && !!mongoForm.database.trim()
    }
    if (isElasticsearchMode.value) {
      return !!esForm.host.trim() && !!esForm.port
    }
    if (isOpenApiMode.value) {
      if (!openApiForm.specUrl.trim()) return false
      if (openApiForm.allowedMethods.length === 0) return false
      if (openApiForm.authType !== 'NONE' && !isEdit.value && !(form.authToken ?? '').trim()) return false
      return true
    }
    if (isSshMode.value) {
      return !!sshForm.host.trim()
        && !!sshForm.port
        && !!sshForm.username.trim()
        && (isEdit.value || !!(form.authToken ?? '').trim())
    }
    if (isDroneMode.value) {
      return !!droneForm.baseUrl.trim()
        && !!droneForm.repo.trim()
        && (isEdit.value || !!(form.authToken ?? '').trim())
    }
    if (isKvMode.value) {
      return !!kvForm.host.trim() && !!kvForm.port && !!kvForm.namespace.trim() && !!kvForm.keyPrefix.trim()
    }
    if (!form.endpoint.trim()) return false
    if (isAliyunMode.value && !aliyunForm.accessKeyId.trim()) return false
    if (isAliyunMode.value && !isEdit.value && !(form.authToken ?? '').trim()) return false
    if (form.transportType === 'STDIO' && !(form.extraConfig ?? '').trim()) return false
    return true
  })

  const endpointLabel = computed(() => {
    if (form.transportType === 'STDIO') return '命令'
    if (form.transportType === 'ALIYUN_DNS') return 'DNS Endpoint'
    if (form.transportType === 'ALIYUN_OSS') return 'OSS Endpoint'
    if (form.transportType === 'REDIS') return 'Redis MCP Endpoint'
    if (form.transportType === 'MONGODB') return 'MongoDB Endpoint'
    if (form.transportType === 'ELASTICSEARCH') return 'Elasticsearch Endpoint'
    if (form.transportType === 'SSH') return 'SSH Host'
    if (isKvMode.value) return 'KV API Endpoint'
    return 'Endpoint URL'
  })
  const endpointPlaceholder = computed(() => {
    switch (form.transportType) {
      case 'STDIO':
        return '例：node 或 /usr/local/bin/mcp-server'
      case 'SSE':
        return '例：https://example.com/mcp/sse'
      case 'HTTP':
        if (isKvMode.value) return '例：https://kv-mcp.example.internal/mcp'
        return '例：https://example.com/mcp'
      case 'ALIYUN_DNS':
        return 'alidns.cn-hangzhou.aliyuncs.com'
      case 'ALIYUN_OSS':
        return 'https://oss-cn-hangzhou.aliyuncs.com'
      case 'REDIS':
        return 'https://redis-mcp.example.internal/mcp'
      case 'MONGODB':
        return 'mongodb://mongo.internal:27017/database'
      case 'ELASTICSEARCH':
        return 'http://es.internal:9200'
      case 'KV_DATABASE':
        return 'http://kv.internal:8080/kv'
      case 'SSH':
        return 'ssh://server.internal:22'
      default:
      return ''
    }
  })

  function currentEndpoint() {
    if (isOpenApiMode.value) return openApiForm.specUrl.trim()
    if (isRedisMode.value) return redisEndpoint()
    if (isMongoMode.value) return mongoEndpoint()
    if (isElasticsearchMode.value) return esEndpoint()
    if (isSshMode.value) return sshEndpoint()
    if (isKvMode.value) return kvEndpoint()
    if (isDroneMode.value) return droneEndpoint()
    if (form.transportType === 'STDIO') return form.endpoint
    return form.endpoint.trim()
  }

  function currentExtraConfig() {
    if (form.transportType === 'STDIO') return form.extraConfig ?? ''
    if (isAliyunMode.value) return aliyunExtraConfig()
    if (isRedisMode.value) return redisExtraConfig()
    if (isMongoMode.value) return mongoExtraConfig()
    if (isElasticsearchMode.value) return esExtraConfig()
    if (isKvMode.value) return kvExtraConfig()
    if (isOpenApiMode.value) return openApiExtraConfig()
    if (isSshMode.value) return sshExtraConfig()
    if (isDroneMode.value) return droneExtraConfig()
    return form.extraConfig ?? ''
  }

  const connectionChanged = computed(() => {
    if (!editingMode.value) return true
    return currentEndpoint() !== originalEndpoint.value
      || currentExtraConfig() !== originalExtraConfig.value
      || !!(form.authToken ?? '').trim()
  })

  const form = reactive<ImawxMcpProxyPayload>({
    serverName: '',
    // 2026-07-06 重构:默认 transportType 从 'AUTO' 改成 'HTTP' —— AUTO 已删除,
    // user 必须明确 HTTP / SSE / STDIO,默认 HTTP 是最常见的 MCP 接入方式。
    transportType: 'HTTP',
    endpoint: '',
    authToken: '',
    extraConfig: '',
    tags: [],
    remark: ''
  })

  const dbForm = reactive({
    dbType: 'MYSQL' as ImawxDbType,
    host: '',
    port: 3306,
    databaseName: '',
    username: '',
    password: '',
    schemaName: ''
  })

  const aliyunForm = reactive({
    accessKeyId: '',
    domainName: '',
    bucketName: ''
  })

  const redisForm = reactive({
    host: '',
    port: 6379,
    username: '',
    tls: false,
    keyPrefix: '',
    database: 0,
    databases: '',
    readOnly: false,
    timeoutSeconds: 30
  })

  const mongoForm = reactive({
    host: '',
    port: 27017,
    database: '',
    username: '',
    authDatabase: 'admin',
    tls: false,
    collectionPrefix: '',
    timeoutSeconds: 30
  })

  const esForm = reactive({
    host: '',
    port: 9200,
    username: '',
    tls: false,
    indexPrefix: '',
    timeoutSeconds: 30
  })

  const kvForm = reactive({
    host: '',
    port: 8080,
    tls: false,
    basePath: '/kv',
    namespace: '',
    keyPrefix: '',
    timeoutSeconds: 30
  })

  const openApiForm = reactive({
    specUrl: '',
    baseUrl: '',
    authType: 'NONE' as 'NONE' | 'BASIC' | 'BEARER' | 'API_KEY_HEADER',
    basicUsername: '',
    apiKeyHeader: 'X-API-Key',
    allowedMethods: ['get'] as string[],
    operationAllowlist: '',
    operationDenylist: '',
    pathPrefix: '',
    timeoutSeconds: 30,
    authForSpec: true,
    specCacheSeconds: 60
  })

  const sshForm = reactive({
    host: '',
    port: 22,
    username: 'root',
    authType: 'PASSWORD' as 'PASSWORD' | 'PRIVATE_KEY',
    allowedCommandsText: '',
    timeoutSeconds: 30
  })

  /**
   * Drone CI/CD 表单(2026-07-06 加)。
   *
   * <p>baseUrl / repo / insecure / timeoutSeconds 跟后端 DRONE 模板对齐,
   * Personal Access Token 走 form.authToken(跟 SSH/Redis/Mongo 等其它 builtin 一致:
   * 编辑时留空表示不修改,新增时必填)。
   */
  const droneForm = reactive({
    baseUrl: '',
    repo: '',
    insecure: false,
    timeoutSeconds: 30
  })

  const dbNeedsSchema = computed(() => dbForm.dbType === 'POSTGRESQL' || dbForm.dbType === 'ORACLE')

  const dbSchemaPlaceholder = computed(() =>
    dbForm.dbType === 'POSTGRESQL' ? '默认 public，可不填' : '例：ORCL / HR / SCOTT'
  )

  function dbTypeOptionLabel(option: any) {
    const port = option?.ext?.defaultPort ?? option?.defaultPort
    const label = option?.label || option?.desc || ''
    return port ? `${label}（默认端口 ${port}）` : label
  }

  function dbMcpPayload(): ImawxMcpProxyPayload {
    const databaseName = dbForm.databaseName.trim()
    const jdbcUrl = buildJdbcUrl(dbForm.dbType, dbForm.host.trim(), Number(dbForm.port), databaseName)
    return {
      serverName: form.serverName.trim(),
      transportType: dbForm.dbType,
      endpoint: jdbcUrl,
      authToken: dbForm.password.trim() ? dbForm.password : undefined,
      extraConfig: JSON.stringify({
        dbType: dbForm.dbType,
        jdbcUrl,
        username: dbForm.username.trim(),
        schemaName: databaseName || (dbNeedsSchema.value ? dbForm.schemaName.trim() || undefined : undefined)
      }),
      tags: form.tags && form.tags.length > 0 ? [...form.tags] : [],
      remark: form.remark
    }
  }

  function genericBuiltinPayload(): ImawxMcpProxyPayload {
    return dbMcpPayload()
  }

  function defaultDbPort(dbType: ImawxDbType) {
    switch (dbType) {
      case 'POSTGRESQL':
        return 5432
      case 'ORACLE':
        return 1521
      case 'SQLSERVER':
        return 1433
      default:
        return 3306
    }
  }

  function dbTypeFromAccessLeaf(key: string): ImawxDbType | null {
    if (!key.startsWith('DB_')) return null
    const value = key.substring(3)
    return ['MYSQL', 'POSTGRESQL', 'ORACLE', 'SQLSERVER'].includes(value)
      ? value as ImawxDbType
      : null
  }

  function buildJdbcUrl(dbType: ImawxDbType, host: string, port: number, databaseName: string) {
    const db = encodeURIComponent(databaseName)
    switch (dbType) {
      case 'POSTGRESQL':
        return `jdbc:postgresql://${host}:${port}/${db}`
      case 'ORACLE':
        return `jdbc:oracle:thin:@${host}:${port}:${databaseName}`
      case 'SQLSERVER':
        return `jdbc:sqlserver://${host}:${port};databaseName=${databaseName};encrypt=false;trustServerCertificate=true`
      default:
        return `jdbc:mysql://${host}:${port}/${db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
    }
  }

  /**
   * 标签输入区状态(2026-07-01 加)。
   *
   * <p>设计：用 closable ElTag + "添加标签"按钮 → 切换为内联 Input → 回车确认。
   * <ul>
   *   <li>新增 tag：trim + 32 字符截断 + 去重 + 上限 20</li>
   *   <li>删除 tag：点 ElTag 的 × 触发 removeTag</li>
   * </ul>
   * 不用 ElSelect 的 multiple 选项 —— 因为用户要"自己起名字",不是从下拉里选。
   */
  const tagInputVisible = ref(false)
  const tagInputValue = ref('')
  const tagInputRef = ref<InstanceType<typeof ElInput> | null>(null)
  const MAX_TAG_COUNT = 20
  const MAX_TAG_LENGTH = 32

  function showTagInput() {
    tagInputVisible.value = true
    nextTick(() => {
      tagInputRef.value?.focus()
    })
  }

  function confirmTag() {
    const raw = tagInputValue.value.trim()
    if (!raw) {
      // 空字符串 = 取消输入,不报错
      tagInputVisible.value = false
      tagInputValue.value = ''
      return
    }
    // 32 字符截断(防粘贴超长)
    const value = raw.slice(0, MAX_TAG_LENGTH)
    if (!form.tags) {
      form.tags = []
    }
    // 去重
    if (form.tags.includes(value)) {
      ElMessage.warning(`标签 "${value}" 已存在`)
      tagInputVisible.value = false
      tagInputValue.value = ''
      return
    }
    // 上限 20
    if (form.tags.length >= MAX_TAG_COUNT) {
      ElMessage.warning(`最多 ${MAX_TAG_COUNT} 个标签`)
      tagInputVisible.value = false
      tagInputValue.value = ''
      return
    }
    form.tags.push(value)
    tagInputVisible.value = false
    tagInputValue.value = ''
  }

  function removeTag(tag: string) {
    if (!form.tags) return
    const idx = form.tags.indexOf(tag)
    if (idx !== -1) {
      form.tags.splice(idx, 1)
    }
  }

  /**
   * 用户是否已「显式」选过 transportType —— 一旦显式选过，自动推荐就不再覆盖。
   * 关键场景：用户选 SSE 后，watch endpoint 输入新字符，自动推荐不会再跳回 HTTP。
   * 重置时机：用户点编辑时（onOpen）或显式改回 radio。
   */
  const transportTypeManuallySet = ref(false)

  const rules: FormRules = {
    serverName: [
      { required: true, message: '服务名必填', trigger: 'blur' },
      { max: 128, message: '最长 128 字符', trigger: 'blur' }
    ],
    transportType: [{ required: true, message: '传输类型必填', trigger: 'change' }],
    endpoint: [
      { required: true, message: 'Endpoint / 命令必填', trigger: 'blur' },
      { max: 512, message: '最长 512 字符', trigger: 'blur' }
    ],
    dbType: [
      {
        validator: (_rule, _value, callback) => {
          if (!isDbMode.value) return callback()
          if (!dbForm.dbType) return callback(new Error('数据库类型必填'))
          callback()
        },
        trigger: 'change'
      }
    ],
    dbHost: [
      {
        validator: (_rule, _value, callback) => {
          if (!isDbMode.value) return callback()
          if (!dbForm.host.trim()) return callback(new Error('Host 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    dbPort: [
      {
        validator: (_rule, _value, callback) => {
          if (!isDbMode.value) return callback()
          if (!dbForm.port) return callback(new Error('端口必填'))
          callback()
        },
        trigger: 'change'
      }
    ],
    dbName: [
      {
        validator: (_rule, _value, callback) => {
          if (!isDbMode.value) return callback()
          if (!dbForm.databaseName.trim()) return callback(new Error('数据库/Schema 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    dbUsername: [
      {
        validator: (_rule, _value, callback) => {
          if (!isDbMode.value) return callback()
          if (!dbForm.username.trim()) return callback(new Error('用户名必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    dbPassword: [
      {
        validator: (_rule, _value, callback) => {
          if (!isDbMode.value) return callback()
          if (!isEdit.value && !dbForm.password.trim()) return callback(new Error('密码必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    aliyunAccessKeyId: [
      {
        validator: (_rule, _value, callback) => {
          if (!isAliyunMode.value) return callback()
          if (!aliyunForm.accessKeyId.trim()) return callback(new Error('AccessKeyId 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    redisHost: [
      {
        validator: (_rule, _value, callback) => {
          if (!isRedisMode.value) return callback()
          if (!redisForm.host.trim()) return callback(new Error('Redis Host 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    redisPort: [
      {
        validator: (_rule, _value, callback) => {
          if (!isRedisMode.value) return callback()
          if (!redisForm.port) return callback(new Error('Redis 端口必填'))
          callback()
        },
        trigger: 'change'
      }
    ],
    mongoHost: [
      {
        validator: (_rule, _value, callback) => {
          if (!isMongoMode.value) return callback()
          if (!mongoForm.host.trim()) return callback(new Error('MongoDB Host 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    mongoPort: [
      {
        validator: (_rule, _value, callback) => {
          if (!isMongoMode.value) return callback()
          if (!mongoForm.port) return callback(new Error('MongoDB 端口必填'))
          callback()
        },
        trigger: 'change'
      }
    ],
    mongoDatabase: [
      {
        validator: (_rule, _value, callback) => {
          if (!isMongoMode.value) return callback()
          if (!mongoForm.database.trim()) return callback(new Error('MongoDB 数据库必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    esHost: [
      {
        validator: (_rule, _value, callback) => {
          if (!isElasticsearchMode.value) return callback()
          if (!esForm.host.trim()) return callback(new Error('Elasticsearch Host 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    esPort: [
      {
        validator: (_rule, _value, callback) => {
          if (!isElasticsearchMode.value) return callback()
          if (!esForm.port) return callback(new Error('Elasticsearch 端口必填'))
          callback()
        },
        trigger: 'change'
      }
    ],
    sshHost: [
      {
        validator: (_rule, _value, callback) => {
          if (!isSshMode.value) return callback()
          if (!sshForm.host.trim()) return callback(new Error('SSH Host 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    sshPort: [
      {
        validator: (_rule, _value, callback) => {
          if (!isSshMode.value) return callback()
          if (!sshForm.port) return callback(new Error('SSH 端口必填'))
          callback()
        },
        trigger: 'change'
      }
    ],
    sshUsername: [
      {
        validator: (_rule, _value, callback) => {
          if (!isSshMode.value) return callback()
          if (!sshForm.username.trim()) return callback(new Error('SSH 用户名必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    kvHost: [
      {
        validator: (_rule, _value, callback) => {
          if (!isKvMode.value) return callback()
          if (!kvForm.host.trim()) return callback(new Error('KV Host 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    kvPort: [
      {
        validator: (_rule, _value, callback) => {
          if (!isKvMode.value) return callback()
          if (!kvForm.port) return callback(new Error('KV 端口必填'))
          callback()
        },
        trigger: 'change'
      }
    ],
    kvNamespace: [
      {
        validator: (_rule, _value, callback) => {
          if (!isKvMode.value) return callback()
          if (!kvForm.namespace.trim()) return callback(new Error('Namespace 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    kvKeyPrefix: [
      {
        validator: (_rule, _value, callback) => {
          if (!isKvMode.value) return callback()
          if (!kvForm.keyPrefix.trim()) return callback(new Error('Key Prefix 必填'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    openApiSpecUrl: [
      {
        validator: (_rule, _value, callback) => {
          if (!isOpenApiMode.value) return callback()
          if (!openApiForm.specUrl.trim()) return callback(new Error('OpenAPI 文档 URL 必填'))
          if (!/^https?:\/\//i.test(openApiForm.specUrl.trim())) return callback(new Error('文档 URL 必须以 http:// 或 https:// 开头'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    openApiBaseUrl: [
      {
        validator: (_rule, _value, callback) => {
          if (!isOpenApiMode.value || !openApiForm.baseUrl.trim()) return callback()
          if (!/^https?:\/\//i.test(openApiForm.baseUrl.trim())) return callback(new Error('Base URL 必须以 http:// 或 https:// 开头'))
          callback()
        },
        trigger: 'blur'
      }
    ],
    extraConfig: [
      {
        validator: (_rule, value, callback) => {
          if (form.transportType !== 'STDIO') return callback()
          if (!value || !value.trim()) return callback(new Error('STDIO 必填扩展配置'))
          try {
            const obj = JSON.parse(value)
            if (typeof obj !== 'object' || obj === null) {
              return callback(new Error('必须是 JSON 对象'))
            }
            if (obj.args !== undefined && !Array.isArray(obj.args)) {
              return callback(new Error('args 必须是数组'))
            }
            if (obj.env !== undefined && (typeof obj.env !== 'object' || Array.isArray(obj.env))) {
              return callback(new Error('env 必须是对象'))
            }
            callback()
          } catch (e) {
            callback(new Error('JSON 解析失败：' + (e as Error).message))
          }
        },
        trigger: 'blur'
      }
    ]
  }

  /**
   * 按 endpoint 形态自动推荐 transportType（用户没显式选过时生效）。
   *
   * <p>启发式：
   * <ul>
   *   <li>URL 含 {@code /sse} 或 {@code text/event-stream} → SSE</li>
   *   <li>URL 是 {@code http://} / {@code https://} 无 SSE 特征 → STREAMABLE_HTTP
   *     （MCP 2.0 推荐协议，2025-03+ 规范默认。HttpClientStreamableHttpTransport
   *     兼容老 HTTP JSON-RPC,新建连接都按新协议走,行为差异由 server 端判断;
   *     老 MCP Server endpoint 如果不支持 Streamable,用户可手动切回 HTTP）</li>
   *   <li>空字符串 → 不动（保留当前 radio）</li>
   *   <li>其他（命令路径，无协议头）→ STDIO</li>
   * </ul>
   */
  function recommendTransportType(endpoint: string): ImawxMcpTransport | null {
    const trimmed = (endpoint ?? '').trim()
    if (!trimmed) return null
    const lower = trimmed.toLowerCase()
    if (lower.startsWith('http://') || lower.startsWith('https://')) {
      // SSE 协议特征：路径含 /sse、或 text/event-stream
      if (/\/sse(\/|$|\?)/i.test(lower) || /event-stream/i.test(lower) || /\/messages\?/i.test(lower)) {
        return 'SSE'
      }
      return 'HTTP'
    }
    if (lower === 'alidns.cn-hangzhou.aliyuncs.com') return 'ALIYUN_DNS'
    return 'STDIO'
  }

  function resetCreateForm() {
    accessSource.value = 'EXTERNAL'
    selectedTemplateKey.value = externalTemplate.value?.key ?? 'EXTERNAL_MCP'
    accessPath.value = ['EXTERNAL', 'HTTP']
    selectedAccessValue.value = 'EXTERNAL_HTTP'
    form.serverName = ''
    form.transportType = 'HTTP'
    form.endpoint = ''
    form.authToken = ''
    form.extraConfig = ''
    form.tags = []
    form.remark = ''
    resetDbForm()
    resetMongoForm()
    resetEsForm()
    resetKvForm()
    resetOpenApiForm()
    resetSshForm()
    resetDroneForm()
    aliyunForm.accessKeyId = ''
    aliyunForm.domainName = ''
    aliyunForm.bucketName = ''
    redisForm.host = ''
    redisForm.port = 6379
    redisForm.username = ''
    redisForm.tls = false
    redisForm.keyPrefix = ''
    redisForm.databases = ''
    redisForm.timeoutSeconds = 30
    originalEndpoint.value = ''
    originalExtraConfig.value = ''
    editingMode.value = false
  }

  function onOpen() {
    loadTemplates()
    transportTypeManuallySet.value = false
    selectedTemplateKey.value = ''
    testState.value = 'idle'
    testToolCount.value = 0
    // 重置标签输入态 —— 关闭上一个 dialog 后再打开新 dialog 避免残留
    tagInputVisible.value = false
    tagInputValue.value = ''
    if (props.record) {
      accessSource.value = ['MYSQL', 'POSTGRESQL', 'ORACLE', 'SQLSERVER', 'KV_DATABASE', 'ALIYUN_DNS', 'ALIYUN_OSS', 'REDIS', 'MONGODB', 'ELASTICSEARCH', 'OPENAPI', 'SSH', 'DRONE'].includes(props.record.transportType)
        ? 'BUILTIN'
        : 'EXTERNAL'
      selectedTemplateKey.value = templateOptions.value.find((t) => t.transportType === props.record?.transportType)?.key ?? ''
      accessPath.value = accessSource.value === 'BUILTIN'
        ? builtinAccessPath(selectedTemplateKey.value)
        : ['EXTERNAL', props.record.transportType]
      selectedAccessValue.value = accessValueForTransport(props.record.transportType)
      form.serverName = props.record.serverName
      form.transportType = props.record.transportType as ImawxMcpTransport
      form.endpoint = props.record.endpoint
      form.authToken = '' // 编辑时留空，存明文 null 表示不改
      form.extraConfig = props.record.extraConfig ?? ''
      parseAliyunExtraConfig(form.extraConfig)
      parseRedisExtraConfig(form.extraConfig)
      parseMongoExtraConfig(form.extraConfig)
      parseEsExtraConfig(form.extraConfig)
      parseKvExtraConfig(form.extraConfig)
      parseOpenApiExtraConfig(form.extraConfig)
      parseSshExtraConfig(form.extraConfig)
      parseDroneExtraConfig(form.extraConfig)
      parseDbExtraConfig(form.extraConfig)
      form.tags = [...(props.record.tags ?? [])] // 拷贝一份,避免直接绑 record 引用
      form.remark = props.record.remark ?? ''
      transportTypeManuallySet.value = true // 编辑时已是用户「确定」的值

      // 2026-07-01 改：编辑时记录原始 endpoint/extraConfig 快照,
      // 如果三个关键字段都没变 → 直接置 passed,保存按钮立即可用;
      // 用户改了任一字段 → watch 会 reset 回 idle,强制重新测试。
      originalEndpoint.value = currentEndpoint()
      originalExtraConfig.value = currentExtraConfig()
      editingMode.value = true
      // authToken 编辑时永远空字符串(form.authToken = ''),不算"改";
      // endpoint / extraConfig 使用 current* 规范化后比较,避免 JSON 格式/内部 MCP 派生字段误判。
      if (!connectionChanged.value) {
        testState.value = 'passed'
        testToolCount.value = props.record.toolCount ?? 0
      }
    } else {
      resetCreateForm()
    }
    // 清校验状态
    nextTick(() => formRef.value?.clearValidate())
  }

  /**
   * 表单关键字段变化 → 重置 testState 为 idle(2026-07-01 改)。
   *
   * <p>关键字段：影响连通性的字段。
   * <ul>
   *   <li>{@code endpoint}: 编辑时跟 originalEndpoint 比,变了 = reset</li>
   *   <li>{@code extraConfig}: 编辑时跟 originalExtraConfig 比,变了 = reset(仅 STDIO 生效)</li>
   *   <li>{@code authToken}: 编辑模式下从空变成非空 = reset("留空表示不修改"语义,
   *       用户主动填了新值才视为改);新增模式下任何变化都 reset</li>
   * </ul>
   * serverName / tags / remark 不影响连接 → 不 reset,改这些字段可以直接保存。
   *
   * <p>transportType 在编辑时 disabled(不会变),所以不加进 watch。
   */
  watch(
    connectionChanged,
    (changed) => {
      if (!editingMode.value || !changed) return
      if (testState.value === 'passed' || testState.value === 'failed') {
        testState.value = 'idle'
        testToolCount.value = 0
      }
    }
  )

  watch(
    () => ({
      endpoint: form.endpoint,
      authToken: form.authToken,
      extraConfig: form.extraConfig
    }),
    () => {
      // 新增模式下,任何字段变化都 reset。
      // 编辑模式下,只看规范化后的连接配置;serverName/tags/remark 不影响保存。
      const endpointChanged = editingMode.value
        ? currentEndpoint() !== originalEndpoint.value
        : form.endpoint !== originalEndpoint.value
      const extraConfigChanged = editingMode.value
        ? currentExtraConfig() !== originalExtraConfig.value
        : form.extraConfig !== originalExtraConfig.value
      const authTokenFilledInEditing = editingMode.value && (form.authToken ?? '').trim() !== ''
      if (!endpointChanged && !extraConfigChanged && !authTokenFilledInEditing) {
        return
      }
      if (testState.value === 'passed' || testState.value === 'failed') {
        testState.value = 'idle'
        testToolCount.value = 0
      }
    }
  )

  // 监听 endpoint 输入：自动推荐 transportType（用户没显式选过时）
  // 2026-07-06 重构：AUTO 探测逻辑移除后，watch 只剩启发式（URL → HTTP / 命令 → STDIO）。
  // 不再调 runProbe —— user 必须手动确认 transportType。
  watch(
    () => form.endpoint,
    (v) => {
      if (isDbMode.value) return
      if (transportTypeManuallySet.value) return
      // 启发式:URL → HTTP(默认兜底),命令 → STDIO
      const recommended = recommendTransportType(v)
      if (recommended && recommended !== form.transportType) {
        form.transportType = recommended
      }
    }
  )

  watch(
    () => dbForm.dbType,
    (v) => {
      if (isDbMode.value) {
        dbForm.port = defaultDbPort(v)
      }
    }
  )

  watch(
    () => ({
      host: sshForm.host,
      port: sshForm.port,
      username: sshForm.username,
      allowedCommandsText: sshForm.allowedCommandsText,
      timeoutSeconds: sshForm.timeoutSeconds
    }),
    () => {
      if (!isSshMode.value) return
      if (editingMode.value) {
        form.extraConfig = sshExtraConfig()
      }
      if (testState.value === 'passed' || testState.value === 'failed') {
        testState.value = 'idle'
        testToolCount.value = 0
      }
    }
  )

  // 监听 transportType radio:用户手动改了就标记,下次不再自动覆盖
  // 2026-07-06 重构:AUTO 切回 + runProbe 重新探测的逻辑已删除 —— 没有 AUTO 了。
  watch(
    () => form.transportType,
    (v, old) => {
      if (isEdit.value) return
      if (old && v !== old) {
        transportTypeManuallySet.value = true
      }
    }
  )

  async function handleSubmit() {
    if (!formRef.value) return
    if (!canSave.value) {
      ElMessage.warning(isEdit.value ? '连接配置已变更，请先测试通过后再保存' : '请先点「测试」并确认通过后再保存')
      return
    }
    try {
      await formRef.value.validate()
    } catch {
      return
    }
    saving.value = true
    try {
      // 2026-07-02 改:输入框全部去掉 v-model.trim(改成保存时 trim)——
      // .trim modifier 在 ElInput 里跟中文输入法/IME 候选词冲突,空格可能输不进去;
      // STDIO 命令路径(可能含空格)被 trim 后会截断。
      // 保存时按 transportType 分别处理:
      //   HTTP/SSE/AUTO → serverName/endpoint 都 trim(URL/名称不该有首尾空格)
      //   STDIO → serverName trim,endpoint 保留原始(命令可能含空格)
      if (isDbMode.value) {
        const payload = dbMcpPayload()
        if (props.record) {
          if (!dbForm.password.trim()) delete payload.authToken
          await updateMcpProxySys(props.record.id, payload)
        } else {
          await createMcpProxySys(payload)
        }
        ElMessage.success('数据库 MCP 保存成功')
        emit('saved')
        emit('update:visible', false)
        return
      }
      if (isAliyunMode.value) {
        form.extraConfig = aliyunExtraConfig()
      }
      if (isRedisMode.value) {
        form.extraConfig = redisExtraConfig()
        form.endpoint = redisEndpoint()
      }
      if (isMongoMode.value) {
        form.extraConfig = mongoExtraConfig()
        form.endpoint = mongoEndpoint()
      }
      if (isElasticsearchMode.value) {
        form.extraConfig = esExtraConfig()
        form.endpoint = esEndpoint()
      }
      if (isKvMode.value) {
        form.extraConfig = kvExtraConfig()
        form.endpoint = kvEndpoint()
      }
      if (isOpenApiMode.value) {
        form.extraConfig = openApiExtraConfig()
        form.endpoint = openApiForm.specUrl.trim()
      }
      if (isSshMode.value) {
        form.extraConfig = sshExtraConfig()
        form.endpoint = sshEndpoint()
      }
      if (isDroneMode.value) {
        form.extraConfig = droneExtraConfig()
        form.endpoint = droneEndpoint()
      }
      const isStdio = form.transportType === 'STDIO'
      const payload: ImawxMcpProxyPayload = {
        serverName: form.serverName.trim(),
        transportType: form.transportType,
        endpoint: currentEndpoint(),
        extraConfig: isStdio || isAliyunMode.value || isNoSqlMode.value || isKvMode.value || isOpenApiMode.value || isSshMode.value || isDroneMode.value ? form.extraConfig : undefined,
        tags: form.tags && form.tags.length > 0 ? [...form.tags] : [],
        remark: form.remark
      }
      if (form.authToken && form.authToken.trim()) {
        payload.authToken = form.authToken.trim()
      }
      if (props.record) {
        await updateMcpProxySys(props.record.id, payload)
      } else {
        // 2026-07-01 改：后端新增后会自动同步一次,响应带回 sync 结果,前端按 success 弹不同 message
        const result = await createMcpProxySys(payload)
        if (result?.sync) {
          if (result.sync.success) {
            ElMessage.success('保存成功，自动同步完成')
          } else {
            // 黄色提示 —— records 已落库,同步失败只是没拉远端 tool,可在卡片抽屉里手动重试
            ElMessage({
              type: 'warning',
              message: `保存成功,但自动同步失败:${result.sync.errorMessage ?? result.sync.errorCode ?? '未知错误'}(可在抽屉里手动重试)`
            })
          }
        }
      }
      emit('saved')
      emit('update:visible', false)
    } catch {
      // 错误已弹
    } finally {
      saving.value = false
    }
  }

  /**
   * 「测试」按钮 → 调 validateMcpProxySys 探活（不落库）。
   * 通过 → testState = 'passed',启用「保存」；失败 → testState = 'failed',禁用「保存」。
   */
  async function handleTest() {
    if (!formRef.value) return
    try {
      await formRef.value.validate()
    } catch {
      return
    }
    testState.value = 'testing'
    try {
      if (isDbMode.value) {
        const tools = await validateMcpProxySys(dbMcpPayload())
        testToolCount.value = tools?.length ?? 0
        testState.value = 'passed'
        ElMessage.success(`测试通过，发现 ${testToolCount.value} 个 Tool`)
        return
      }
      const payload: ImawxMcpProxyPayload = {
        serverName: form.serverName,
        transportType: form.transportType,
        endpoint: currentEndpoint(),
        extraConfig: isAliyunMode.value
          ? aliyunExtraConfig()
          : isRedisMode.value
            ? redisExtraConfig()
            : isMongoMode.value
              ? mongoExtraConfig()
              : isElasticsearchMode.value
                ? esExtraConfig()
                : isSshMode.value
                  ? sshExtraConfig()
                  : isKvMode.value
                    ? kvExtraConfig()
                    : isOpenApiMode.value
                      ? openApiExtraConfig()
                      : isDroneMode.value
                        ? droneExtraConfig()
                        : form.transportType === 'STDIO'
                          ? form.extraConfig
                          : undefined,
        tags: form.tags && form.tags.length > 0 ? [...form.tags] : [],
        remark: form.remark
      }
      if (form.authToken && form.authToken.trim()) {
        payload.authToken = form.authToken.trim()
      }
      const tools = await validateMcpProxySys(payload)
      testToolCount.value = tools?.length ?? 0
      testState.value = 'passed'
      ElMessage.success(`测试通过，发现 ${testToolCount.value} 个 Tool`)
    } catch (e) {
      testState.value = 'failed'
      testToolCount.value = 0
      // 错误 message 由 request util 弹 ElMessage.error —— 不用再手动处理
    }
  }

  function aliyunExtraConfig() {
    const obj: Record<string, string> = {
      accessKeyId: aliyunForm.accessKeyId.trim()
    }
    if (isAliyunDnsMode.value && aliyunForm.domainName.trim()) {
      obj.domainName = aliyunForm.domainName.trim()
    }
    if (isAliyunOssMode.value && aliyunForm.bucketName.trim()) {
      obj.bucketName = aliyunForm.bucketName.trim()
    }
    return JSON.stringify(obj)
  }

  function parseAliyunExtraConfig(extraConfig?: string) {
    aliyunForm.accessKeyId = ''
    aliyunForm.domainName = ''
    aliyunForm.bucketName = ''
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      aliyunForm.accessKeyId = String(obj.accessKeyId ?? '')
      aliyunForm.domainName = String(obj.domainName ?? obj.domain ?? '')
      aliyunForm.bucketName = String(obj.bucketName ?? obj.bucket ?? '')
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function redisExtraConfig() {
    const databases = parseRedisDatabases(redisForm.databases)
    return JSON.stringify({
      host: redisForm.host.trim(),
      port: redisForm.port,
      username: redisForm.username.trim(),
      tls: redisForm.tls,
      keyPrefix: redisForm.keyPrefix.trim(),
      database: redisForm.database,
      databases,
      readOnly: redisForm.readOnly,
      timeoutSeconds: redisForm.timeoutSeconds
    })
  }

  function redisEndpoint() {
    const scheme = redisForm.tls ? 'rediss' : 'redis'
    const databases = parseRedisDatabases(redisForm.databases)
    return databases.length === 1
      ? `${scheme}://${redisForm.host.trim()}:${redisForm.port}/${databases[0]}`
      : `${scheme}://${redisForm.host.trim()}:${redisForm.port}`
  }

  function parseRedisDatabases(value: string) {
    return value
      .split(/[\s,;，；]+/)
      .map((item) => item.trim())
      .filter(Boolean)
      .map((item) => Number(item))
      .filter((item, index, arr) => Number.isInteger(item) && item >= 0 && item <= 15 && arr.indexOf(item) === index)
  }

  /**
   * DB 表单 reset：把 dbForm 字段全清掉回到 MYSQL/3306 默认。
   */
  function resetDbForm() {
    dbForm.dbType = 'MYSQL'
    dbForm.host = ''
    dbForm.port = defaultDbPort('MYSQL')
    dbForm.databaseName = ''
    dbForm.username = ''
    dbForm.password = ''
    dbForm.schemaName = ''
  }

  /**
   * DB extraConfig 解析：从 mcp_backend_extension.config_json 拆回
   * dbType/host/port/databaseName/username/schemaName。
   * jdbcUrl 解析失败时整段忽略,不让弹窗打不开。
   */
  function parseDbExtraConfig(extraConfig?: string) {
    resetDbForm()
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      const dbType = (obj.dbType ?? 'MYSQL') as ImawxDbType
      dbForm.dbType = dbType
      const jdbcUrl = String(obj.jdbcUrl ?? '')
      if (jdbcUrl) {
        // buildJdbcUrl 反向解析:复用"拆 host/port/database"的逻辑,如果不支持就 fallback
        const parsed = reverseJdbcUrl(jdbcUrl)
        if (parsed) {
          dbForm.host = parsed.host
          dbForm.port = parsed.port
          dbForm.databaseName = parsed.databaseName
        } else {
          // 解析失败保留空,admin 重新填
          dbForm.host = ''
          dbForm.port = defaultDbPort(dbType)
        }
      } else {
        dbForm.port = defaultDbPort(dbType)
      }
      dbForm.username = String(obj.username ?? '')
      dbForm.schemaName = String(obj.schemaName ?? '')
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function reverseJdbcUrl(jdbcUrl: string): { host: string; port: number; databaseName: string } | null {
    // 简单解析 jdbc:dbtype://host:port/database — 跟 buildJdbcUrl 配套
    // POSTGRESQL: jdbc:postgresql://host:port/db
    // MYSQL:      jdbc:mysql://host:port/db?params
    // SQLSERVER:  jdbc:sqlserver://host:port;databaseName=master;...
    // ORACLE:     jdbc:oracle:thin:@host:port:sid
    try {
      const cleaned = jdbcUrl.replace(/^jdbc:/, '')
      // SQLSERVER: jdbc:sqlserver://host:port;databaseName=master;...
      if (cleaned.startsWith('sqlserver://')) {
        const rest = cleaned.slice('sqlserver://'.length)
        const [hostPort, ...restParts] = rest.split(';')
        const [host, portStr] = hostPort.split(':')
        const port = Number(portStr) || 1433
        let databaseName = ''
        for (const part of restParts) {
          const [k, v] = part.split('=')
          if (k?.toLowerCase() === 'databasename') databaseName = v ?? ''
        }
        return { host: host ?? '', port, databaseName }
      }
      // ORACLE: jdbc:oracle:thin:@host:port:sid
      if (cleaned.startsWith('oracle:thin:@')) {
        const rest = cleaned.slice('oracle:thin:@'.length)
        const [hostPort, sid] = rest.split(':')
        const [host, portStr] = hostPort.split(':')
        const port = Number(portStr) || 1521
        return { host: host ?? '', port, databaseName: sid ?? '' }
      }
      // POSTGRESQL / MYSQL: jdbc:dbtype://host:port/db?params
      const match = cleaned.match(/^([a-z]+):\/\/([^:/]+):(\d+)\/([^?]+)/)
      if (match) {
        return { host: match[2], port: Number(match[3]), databaseName: match[4] }
      }
      return null
    } catch {
      return null
    }
  }

  function resetMongoForm() {
    mongoForm.host = ''
    mongoForm.port = 27017
    mongoForm.database = ''
    mongoForm.username = ''
    mongoForm.authDatabase = 'admin'
    mongoForm.tls = false
    mongoForm.collectionPrefix = ''
    mongoForm.timeoutSeconds = 30
  }

  function mongoExtraConfig() {
    return JSON.stringify({
      host: mongoForm.host.trim(),
      port: mongoForm.port,
      database: mongoForm.database.trim(),
      username: mongoForm.username.trim(),
      authDatabase: mongoForm.authDatabase.trim() || 'admin',
      tls: mongoForm.tls,
      collectionPrefix: mongoForm.collectionPrefix.trim(),
      timeoutSeconds: mongoForm.timeoutSeconds
    })
  }

  function mongoEndpoint() {
    return `mongodb://${mongoForm.host.trim()}:${mongoForm.port}/${encodeURIComponent(mongoForm.database.trim())}`
  }

  function parseMongoExtraConfig(extraConfig?: string) {
    resetMongoForm()
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      mongoForm.host = String(obj.host ?? '')
      const port = Number(obj.port ?? 27017)
      mongoForm.port = Number.isFinite(port) ? port : 27017
      mongoForm.database = String(obj.database ?? '')
      mongoForm.username = String(obj.username ?? '')
      mongoForm.authDatabase = String(obj.authDatabase ?? 'admin')
      mongoForm.tls = Boolean(obj.tls)
      mongoForm.collectionPrefix = String(obj.collectionPrefix ?? '')
      const timeoutSeconds = Number(obj.timeoutSeconds ?? 30)
      mongoForm.timeoutSeconds = Number.isFinite(timeoutSeconds) ? timeoutSeconds : 30
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function resetEsForm() {
    esForm.host = ''
    esForm.port = 9200
    esForm.username = ''
    esForm.tls = false
    esForm.indexPrefix = ''
    esForm.timeoutSeconds = 30
  }

  function esExtraConfig() {
    return JSON.stringify({
      host: esForm.host.trim(),
      port: esForm.port,
      username: esForm.username.trim(),
      tls: esForm.tls,
      indexPrefix: esForm.indexPrefix.trim(),
      timeoutSeconds: esForm.timeoutSeconds
    })
  }

  function esEndpoint() {
    const scheme = esForm.tls ? 'https' : 'http'
    return `${scheme}://${esForm.host.trim()}:${esForm.port}`
  }

  function parseEsExtraConfig(extraConfig?: string) {
    resetEsForm()
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      esForm.host = String(obj.host ?? '')
      const port = Number(obj.port ?? 9200)
      esForm.port = Number.isFinite(port) ? port : 9200
      esForm.username = String(obj.username ?? '')
      esForm.tls = Boolean(obj.tls)
      esForm.indexPrefix = String(obj.indexPrefix ?? '')
      const timeoutSeconds = Number(obj.timeoutSeconds ?? 30)
      esForm.timeoutSeconds = Number.isFinite(timeoutSeconds) ? timeoutSeconds : 30
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function kvEndpoint() {
    const scheme = kvForm.tls ? 'https' : 'http'
    const path = normalizePath(kvForm.basePath || '/kv')
    return `${scheme}://${kvForm.host.trim()}:${kvForm.port}${path}`
  }

  function normalizePath(path: string) {
    const trimmed = path.trim()
    if (!trimmed) return '/kv'
    return trimmed.startsWith('/') ? trimmed : `/${trimmed}`
  }

  function parseRedisExtraConfig(extraConfig?: string) {
    redisForm.host = ''
    redisForm.port = 6379
    redisForm.username = ''
    redisForm.tls = false
    redisForm.keyPrefix = ''
    redisForm.database = 0
    redisForm.databases = ''
    redisForm.readOnly = false
    redisForm.timeoutSeconds = 30
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      redisForm.host = String(obj.host ?? '')
      const port = Number(obj.port ?? 6379)
      redisForm.port = Number.isFinite(port) ? port : 6379
      redisForm.username = String(obj.username ?? '')
      redisForm.tls = Boolean(obj.tls)
      redisForm.keyPrefix = String(obj.keyPrefix ?? '')
      const database = Number(obj.database ?? 0)
      redisForm.database = Number.isInteger(database) && database >= 0 && database <= 15 ? database : 0
      if (Array.isArray(obj.databases)) {
        redisForm.databases = obj.databases.map(String).join(',')
      } else if (obj.database !== undefined && obj.database !== null && String(obj.database).trim() !== '') {
        redisForm.databases = String(obj.database)
      } else {
        redisForm.databases = ''
      }
      redisForm.readOnly = Boolean(obj.readOnly)
      const timeoutSeconds = Number(obj.timeoutSeconds ?? 30)
      redisForm.timeoutSeconds = Number.isFinite(timeoutSeconds) ? timeoutSeconds : 30
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function resetKvForm() {
    kvForm.host = ''
    kvForm.port = 8080
    kvForm.tls = false
    kvForm.basePath = '/kv'
    kvForm.namespace = ''
    kvForm.keyPrefix = ''
    kvForm.timeoutSeconds = 30
  }

  function kvExtraConfig() {
    return JSON.stringify({
      host: kvForm.host.trim(),
      port: kvForm.port,
      tls: kvForm.tls,
      basePath: normalizePath(kvForm.basePath || '/kv'),
      namespace: kvForm.namespace.trim(),
      keyPrefix: kvForm.keyPrefix.trim(),
      timeoutSeconds: kvForm.timeoutSeconds
    })
  }

  function parseKvExtraConfig(extraConfig?: string) {
    resetKvForm()
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      kvForm.host = String(obj.host ?? '')
      const port = Number(obj.port ?? 8080)
      kvForm.port = Number.isFinite(port) ? port : 8080
      kvForm.tls = Boolean(obj.tls)
      kvForm.basePath = String(obj.basePath ?? '/kv')
      kvForm.namespace = String(obj.namespace ?? '')
      kvForm.keyPrefix = String(obj.keyPrefix ?? obj.prefix ?? '')
      const timeoutSeconds = Number(obj.timeoutSeconds ?? 30)
      kvForm.timeoutSeconds = Number.isFinite(timeoutSeconds) ? timeoutSeconds : 30
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function resetOpenApiForm() {
    openApiForm.specUrl = ''
    openApiForm.baseUrl = ''
    openApiForm.authType = 'NONE'
    openApiForm.basicUsername = ''
    openApiForm.apiKeyHeader = 'X-API-Key'
    openApiForm.allowedMethods = ['get']
    openApiForm.operationAllowlist = ''
    openApiForm.operationDenylist = ''
    openApiForm.pathPrefix = ''
    openApiForm.timeoutSeconds = 30
    openApiForm.authForSpec = true
    openApiForm.specCacheSeconds = 60
  }

  function openApiExtraConfig() {
    return JSON.stringify({
      specUrl: openApiForm.specUrl.trim(),
      baseUrl: openApiForm.baseUrl.trim(),
      authType: openApiForm.authType,
      basicUsername: openApiForm.basicUsername.trim(),
      apiKeyHeader: openApiForm.apiKeyHeader.trim() || 'X-API-Key',
      allowedMethods: openApiForm.allowedMethods,
      operationAllowlist: openApiForm.operationAllowlist,
      operationDenylist: openApiForm.operationDenylist,
      pathPrefix: openApiForm.pathPrefix.trim(),
      timeoutSeconds: openApiForm.timeoutSeconds,
      authForSpec: openApiForm.authForSpec,
      specCacheSeconds: openApiForm.specCacheSeconds
    })
  }

  function parseOpenApiExtraConfig(extraConfig?: string) {
    resetOpenApiForm()
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      openApiForm.specUrl = String(obj.specUrl ?? obj.swaggerUrl ?? obj.openapiUrl ?? '')
      openApiForm.baseUrl = String(obj.baseUrl ?? '')
      const authType = String(obj.authType ?? 'NONE').toUpperCase()
      openApiForm.authType = ['NONE', 'BASIC', 'BEARER', 'API_KEY_HEADER'].includes(authType)
        ? authType as typeof openApiForm.authType
        : 'NONE'
      openApiForm.basicUsername = String(obj.basicUsername ?? obj.username ?? '')
      openApiForm.apiKeyHeader = String(obj.apiKeyHeader ?? 'X-API-Key')
      openApiForm.allowedMethods = Array.isArray(obj.allowedMethods)
        ? obj.allowedMethods.map(String).map((v: string) => v.toLowerCase())
        : ['get']
      openApiForm.operationAllowlist = String(obj.operationAllowlist ?? '')
      openApiForm.operationDenylist = String(obj.operationDenylist ?? '')
      openApiForm.pathPrefix = String(obj.pathPrefix ?? '')
      const timeoutSeconds = Number(obj.timeoutSeconds ?? 30)
      openApiForm.timeoutSeconds = Number.isFinite(timeoutSeconds) ? timeoutSeconds : 30
      openApiForm.authForSpec = obj.authForSpec !== false
      const specCacheSeconds = Number(obj.specCacheSeconds ?? 60)
      openApiForm.specCacheSeconds = Number.isFinite(specCacheSeconds) ? specCacheSeconds : 60
      if (openApiForm.specUrl) {
        form.endpoint = openApiForm.specUrl
      }
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function resetSshForm() {
    sshForm.host = ''
    sshForm.port = 22
    sshForm.username = 'root'
    sshForm.authType = 'PASSWORD'
    sshForm.allowedCommandsText = ''
    sshForm.timeoutSeconds = 30
  }

  function allowedSshCommands() {
    return sshForm.allowedCommandsText
      .split(/\r?\n/)
      .map((item) => item.trim())
      .filter(Boolean)
      .filter((item, index, arr) => arr.indexOf(item) === index)
  }

  function sshExtraConfig() {
    const obj: Record<string, unknown> = {
      host: sshForm.host.trim(),
      port: sshForm.port,
      username: sshForm.username.trim(),
      authType: sshForm.authType,
      allowedCommands: allowedSshCommands(),
      timeoutSeconds: sshForm.timeoutSeconds
    }
    return JSON.stringify(obj)
  }

  function sshEndpoint() {
    return `ssh://${sshForm.host.trim()}:${sshForm.port}`
  }

  function parseSshExtraConfig(extraConfig?: string) {
    resetSshForm()
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      sshForm.host = String(obj.host ?? '')
      const port = Number(obj.port ?? 22)
      sshForm.port = Number.isFinite(port) ? port : 22
      sshForm.username = String(obj.username ?? 'root')
      sshForm.authType = obj.authType === 'PRIVATE_KEY' ? 'PRIVATE_KEY' : 'PASSWORD'
      const commands = Array.isArray(obj.allowedCommands)
        ? obj.allowedCommands.map(String)
        : String(obj.allowedCommands ?? '').split(/[\r\n,;]+/)
      sshForm.allowedCommandsText = commands.map((item: string) => item.trim()).filter(Boolean).join('\n')
      const timeoutSeconds = Number(obj.timeoutSeconds ?? 30)
      sshForm.timeoutSeconds = Number.isFinite(timeoutSeconds) ? timeoutSeconds : 30
      if (sshForm.host) {
        form.endpoint = sshEndpoint()
      }
    } catch {
      // 兼容历史脏配置，不阻断打开弹窗
    }
  }

  function resetDroneForm() {
    droneForm.baseUrl = ''
    droneForm.repo = ''
    droneForm.insecure = false
    droneForm.timeoutSeconds = 30
  }

  function droneExtraConfig() {
    const obj: Record<string, unknown> = {
      baseUrl: droneForm.baseUrl.trim().replace(/\/+$/, ''),
      repo: droneForm.repo.trim().replace(/^\/+|\/+$/g, ''),
      insecure: droneForm.insecure,
      timeoutSeconds: droneForm.timeoutSeconds
    }
    return JSON.stringify(obj)
  }

  function droneEndpoint() {
    return droneForm.baseUrl.trim().replace(/\/+$/, '') || ''
  }

  function parseDroneExtraConfig(extraConfig?: string) {
    resetDroneForm()
    if (!extraConfig?.trim()) return
    try {
      const obj = JSON.parse(extraConfig)
      droneForm.baseUrl = String(obj.baseUrl ?? '')
      droneForm.repo = String(obj.repo ?? '')
      droneForm.insecure = obj.insecure === true
      const timeoutSeconds = Number(obj.timeoutSeconds ?? 30)
      droneForm.timeoutSeconds = Number.isFinite(timeoutSeconds) ? timeoutSeconds : 30
      if (droneForm.baseUrl) {
        form.endpoint = droneEndpoint()
      }
    } catch {
      // 配置 JSON 损坏时不阻断打开弹窗
    }
  }

  function builtinAccessPath(key: string) {
    if (key === 'DB' || key.startsWith('DB_')) return ['BUILTIN', 'DATABASE', key]
    if (key === 'REDIS' || key === 'MONGODB' || key === 'ELASTICSEARCH') return ['BUILTIN', 'NOSQL', key]
    if (key === 'ALIYUN_DNS_OPS' || key === 'ALIYUN_OSS') return ['BUILTIN', 'ALIYUN', key]
    if (key === 'OPENAPI') return ['BUILTIN', 'API', key]
    if (key === 'SSH') return ['BUILTIN', 'OPS', key]
    if (key === 'DRONE') return ['BUILTIN', 'CI', key]
    return ['BUILTIN', key]
  }

  function accessValueForTransport(transportType?: string) {
    const type = String(transportType ?? '').toUpperCase()
    if (['HTTP', 'SSE', 'STDIO', 'STREAMABLE_HTTP'].includes(type)) {
      return `EXTERNAL_${type === 'STREAMABLE_HTTP' ? 'HTTP' : type}`
    }
    if (['MYSQL', 'POSTGRESQL', 'ORACLE', 'SQLSERVER'].includes(type)) {
      return `DB_${type}`
    }
    if (type === 'ALIYUN_DNS') return 'ALIYUN_DNS_OPS'
    return type || 'EXTERNAL_HTTP'
  }
</script>

<style scoped lang="scss">
  /* 固定高度 dialog 用全局 styles/imawx.scss 的 .imawx-dialog-fixed --compact(600px),
     这里不重复。 */

  .imawx-mcp-proxy-form {
    /* label 容器 —— 让 icon 跟在 label 文字后面 */
    &__label {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    &__template-option {
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 2px;
      min-height: 36px;

      small {
        overflow: hidden;
        color: var(--el-text-color-secondary);
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    &__template-note {
      margin-top: 6px;
      font-size: 12px;
      line-height: 1.5;
      color: var(--el-color-warning);
    }

    /* 解释 icon —— 紧贴 label 文字,灰色细线风,不抢视觉 */
    &__icon {
      color: var(--el-text-color-placeholder);
      cursor: help;
      transition: color 0.15s;
      font-size: 14px;

      &:hover {
        color: var(--el-color-primary);
      }
    }

    /* footer 行 —— 左侧测试状态指示 + 右侧按钮组 */
    &__footer {
      display: flex;
      align-items: center;
      width: 100%;
      gap: 8px;
    }

    &__spacer {
      flex: 1 1 auto;
    }

    /* 测试状态指示 —— 通过/失败/未测三种视觉 */
    &__status {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      line-height: 1.5;

      .el-icon {
        font-size: 14px;
      }

      &--ok {
        color: var(--el-color-success);
      }

      &--err {
        color: var(--el-color-danger);
      }

      &--hint {
        color: var(--el-text-color-placeholder);
      }
    }

    /* 标签输入区 —— closable ElTag 列表 + 末尾的"添加标签"按钮 / 内联 Input */
    &__tags {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      width: 100%;
      min-height: 32px;
      padding: 4px 8px;
      border: 1px solid var(--el-border-color);
      border-radius: 4px;
      background: var(--el-fill-color-blank);
      transition: border-color 0.15s;

      &:hover,
      &:focus-within {
        border-color: var(--el-color-primary);
      }
    }

    &__tag-input {
      width: 140px;
    }

    /* 复写 ElInput 在 tag 输入场景的默认 border,跟外层融合 */
    &__tag-input :deep(.el-input__wrapper) {
      padding: 0 8px;
      box-shadow: none;
      background: transparent;

      &.is-focus {
        box-shadow: none;
      }
    }

    &__tag-add {
      padding: 0 8px;
      color: var(--el-text-color-secondary);
    }
  }
</style>
