import {
  LayoutDashboard,
  Server,
  HardDrive,
  FileText,
  Activity,
  Users,
  Settings,
  ShieldCheck,
  ScrollText,
  Cpu,
} from 'lucide-react'
import { type SidebarData } from '../types'

export const sidebarData: SidebarData = {
  user: {
    name: 'admin',
    email: 'admin@starrocks',
    avatar: '',
  },
  teams: [
    {
      name: 'StarRocks',
      logo: LayoutDashboard,
      plan: 'Web Console',
    },
  ],
  navGroups: [
    {
      title: 'Cluster',
      items: [
        { title: 'Overview', url: '/', icon: LayoutDashboard },
        { title: 'System', url: '/system', icon: Server },
        { title: 'Backends', url: '/backends', icon: HardDrive },
        { title: 'HA', url: '/ha', icon: ShieldCheck },
        { title: 'Sessions', url: '/sessions', icon: Users },
        { title: 'Variables', url: '/variables', icon: Settings },
      ],
    },
    {
      title: 'Observability',
      items: [
        { title: 'Queries', url: '/queries', icon: Activity },
        { title: 'Query Profile', url: '/query-profile', icon: ScrollText },
        { title: 'Proc Profiles', url: '/proc-profiles', icon: Cpu },
        { title: 'Logs', url: '/logs', icon: FileText },
      ],
    },
  ],
}
