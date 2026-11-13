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

export type SystemInfo = {
  path: string
  parent: string
  isDir: boolean
  columns: string[]
  rows: (string | null)[][]
}

export type VariableData = {
  config: Record<string, string>
  configError?: string
  variables: { name: string; value: string }[]
}

export type HaInfo = {
  feType: string
  leader: boolean
  journalId: number
  canRead: boolean
  databases: number[] | null
  checkpoint: { version: number; time: number } | null
  nodes: {
    electable: { host: string; port: number }[]
    observer: { host: string; port: number }[]
  }
  frontends: {
    name: string
    host: string
    role: string
    alive: boolean
    queryPort: number
    rpcPort: number
    editLogPort: number
    replayedJournalId: number
    version: string
    lastUpdateTime: number
    startTime: number
    heapUsedPercent: number
    cpuCores: number
    fid: number
  }[]
  removedFrontends: string[] | null
}

export type LogsInfo = {
  dir: string
  level: string
  verboseNames: string[]
  auditNames: string[]
  files: { name: string; size: number; modified: number }[]
}

export type LogContent = {
  name: string
  size: number
  truncated: boolean
  content: string
}

export type ProcProfileFile = {
  type: string
  timestamp: string
  size: number
  filename: string
}

export type ProcProfileList = {
  node: string
  files: ProcProfileFile[]
}

export type BeConfig = { name: string; value: string }

export type BeTable = {
  node: string
  headers: string[]
  rows: string[][]
}

export type BeText = {
  node: string
  text: string
}
