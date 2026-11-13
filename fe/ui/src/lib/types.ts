export type Overview = {
  version: { version: string; commit: string; buildTime: string }
  fe: { type: string; leader: boolean }
  backends: { total: number; alive: number }
  computeNodes: { total: number; alive: number }
  queries: { running: number; finished: number }
  sessions: { total: number }
}

export type Backend = {
  id: number
  host: string
  alive: boolean
  state: string
  version: string
  heartbeatPort: number
  bePort: number
  httpPort: number
  brpcPort: number
  startTime: string
  lastSuccessReportTabletsTime: string
  cpuCores: number
  cpuUsedPermille: number
  memUsedBytes: number
  memLimitBytes: number
  memUsedPct: number
  totalCapacityB: number
  availableCapacityB: number
  dataUsedCapacityB: number
  maxDiskUsedPct: number
  tabletMaxCompactionScore: number
}

export type QueryRow = {
  queryId: string
  user: string
  db: string
  sql: string
  queryType: string
  startTime: number | string
  endTime?: number | string
  execTime?: number
  totalTime?: string
  state: string
  warehouse: string
  dialect?: string
}

export type QueryList = {
  running: QueryRow[]
  finished: QueryRow[]
}

export type SessionRow = {
  id: string
  user: string
  host: string
  db: string
  command: string
  connectionStartTime: string
  time: string
  state: string
  info: string
  isPending: string
  warehouse: string
  cnGroup: string
  catalog: string
  queryId: string
  running: boolean
}
