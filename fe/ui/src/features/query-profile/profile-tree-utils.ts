export type ProfileNode = {
  id: number
  text: string
  depth: number
  children: ProfileNode[]
}

export function parseProfile(text: string): ProfileNode[] {
  const roots: ProfileNode[] = []
  const stack: { indent: number; node: ProfileNode }[] = []
  let id = 0
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const indent = line.length - line.trimStart().length
    while (stack.length > 0 && indent <= stack[stack.length - 1].indent) {
      stack.pop()
    }
    const node: ProfileNode = {
      id: id++,
      text: trimmed,
      depth: stack.length,
      children: [],
    }
    if (stack.length === 0) roots.push(node)
    else stack[stack.length - 1].node.children.push(node)
    stack.push({ indent, node })
  }
  return roots
}

export function nodeIds(nodes: ProfileNode[]): number[] {
  const ids: number[] = []
  const walk = (items: ProfileNode[]) => {
    for (const item of items) {
      ids.push(item.id)
      walk(item.children)
    }
  }
  walk(nodes)
  return ids
}

export function collapsedIds(nodes: ProfileNode[], minDepth: number): number[] {
  const ids: number[] = []
  const walk = (items: ProfileNode[]) => {
    for (const item of items) {
      if (item.depth >= minDepth) ids.push(item.id)
      walk(item.children)
    }
  }
  walk(nodes)
  return ids
}
