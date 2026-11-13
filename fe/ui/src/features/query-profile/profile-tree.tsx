import { useMemo, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import {
  collapsedIds,
  nodeIds,
  parseProfile,
  type ProfileNode,
} from './profile-tree-utils'

export function ProfileTree({ text }: { text: string }) {
  const nodes = useMemo(() => parseProfile(text), [text])
  const [collapsed, setCollapsed] = useState<Set<number>>(
    () => new Set(collapsedIds(nodes, 2))
  )

  return (
    <div>
      <div className='mb-2 flex gap-2'>
        <button
          type='button'
          className='text-xs text-muted-foreground underline hover:text-foreground'
          onClick={() => setCollapsed(new Set())}
        >
          Expand all
        </button>
        <button
          type='button'
          className='text-xs text-muted-foreground underline hover:text-foreground'
          onClick={() => setCollapsed(new Set(nodeIds(nodes)))}
        >
          Collapse all
        </button>
      </div>
      <div className='max-h-[70vh] overflow-auto p-4 font-mono text-xs leading-relaxed'>
        {nodes.map((node) => (
          <TreeNode
            key={node.id}
            node={node}
            collapsed={collapsed}
            onToggle={(id) => {
              setCollapsed((previous) => {
                const next = new Set(previous)
                if (next.has(id)) next.delete(id)
                else next.add(id)
                return next
              })
            }}
          />
        ))}
      </div>
    </div>
  )
}

function TreeNode({
  node,
  collapsed,
  onToggle,
}: {
  node: ProfileNode
  collapsed: Set<number>
  onToggle: (id: number) => void
}) {
  const isCollapsed = collapsed.has(node.id)
  return (
    <div>
      <div
        className='flex items-start rounded hover:bg-muted/60'
        style={{ paddingLeft: node.depth * 16 }}
      >
        {node.children.length > 0 ? (
          <button
            type='button'
            aria-label={isCollapsed ? 'Expand' : 'Collapse'}
            aria-expanded={!isCollapsed}
            className='mt-0.5 shrink-0 text-muted-foreground'
            onClick={() => onToggle(node.id)}
          >
            {isCollapsed ? (
              <ChevronRight className='size-3' />
            ) : (
              <ChevronDown className='size-3' />
            )}
          </button>
        ) : (
          <span className='size-3 shrink-0' />
        )}
        <span className='break-all whitespace-pre-wrap'>{node.text}</span>
      </div>
      {!isCollapsed &&
        node.children.map((child) => (
          <TreeNode
            key={child.id}
            node={child}
            collapsed={collapsed}
            onToggle={onToggle}
          />
        ))}
    </div>
  )
}
