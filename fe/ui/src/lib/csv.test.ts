import { describe, expect, it } from 'vitest'
import { toCsv } from './csv'

describe('toCsv', () => {
  it('escapes commas, quotes and newlines', () => {
    expect(
      toCsv(
        ['id', 'sql'],
        [
          [1, 'select "a", b'],
          [2, 'line1\nline2'],
        ]
      )
    ).toBe('id,sql\r\n1,"select ""a"", b"\r\n2,"line1\nline2"')
  })
})
