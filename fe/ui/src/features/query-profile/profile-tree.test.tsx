import { describe, expect, it } from 'vitest'
import { collapsedIds, nodeIds, parseProfile } from './profile-tree-utils'

describe('parseProfile', () => {
  it('builds a tree from indentation', () => {
    const nodes = parseProfile(
      [
        'Query:',
        '  Summary:',
        '    Total: 1s',
        '  Fragment 0:',
        '    Pipeline:',
      ].join('\n')
    )
    expect(nodes).toHaveLength(1)
    expect(nodes[0].text).toBe('Query:')
    expect(nodes[0].children.map((node) => node.text)).toEqual([
      'Summary:',
      'Fragment 0:',
    ])
    expect(nodes[0].children[0].children[0].text).toBe('Total: 1s')
    expect(nodes[0].children[1].children[0].depth).toBe(2)
  })

  it('skips blank lines and finds collapsible nodes', () => {
    const nodes = parseProfile('Query:\n\n  Summary:\n    A: 1\n')
    expect(nodeIds(nodes)).toHaveLength(3)
    expect(collapsedIds(nodes, 2)).toEqual([2])
  })
})
