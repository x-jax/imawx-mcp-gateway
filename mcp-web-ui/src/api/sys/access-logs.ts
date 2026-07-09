import request from '@/utils/http'

export type ImawxAccessLogResult = 'SUCCESS' | 'FAILED'

export interface ImawxAccessLog {
  id: string
  traceId?: string
  ip: string
  method: string
  uri: string
  result: ImawxAccessLogResult
  status: number
  costMs: number
  hasQuery: number
  userAgent?: string
  userId?: string
  userEmail?: string
  tokenId?: string
  tokenPrefix?: string
  authHeader: number
  createTime: string
}

export interface ImawxAccessLogQuery {
  startTime?: string
  endTime?: string
  keyword?: string
  userEmail?: string
  ip?: string
  method?: string
  result?: ImawxAccessLogResult
  status?: number
  pageNum?: number
  pageSize?: number
}

export interface ImawxAccessLogPage {
  records: ImawxAccessLog[]
  total: number
  pageNum: number
  pageSize: number
}

export function fetchAccessLogsSys(params: ImawxAccessLogQuery) {
  return request.get<ImawxAccessLogPage>({
    url: '/api/sys/access-logs',
    params
  })
}
