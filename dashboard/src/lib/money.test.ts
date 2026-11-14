import { describe, expect, it } from 'vitest'
import { checkTakaInput, formatBasisPoints, formatPoisha, parseSignedTaka, poishaToInputText } from './money'

describe('formatPoisha', () => {
  it.each([
    [0, '৳0.00'],
    [5, '৳0.05'],
    [12050, '৳120.50'],
    [12500000, '৳1,25,000.00'], // lakh grouping
    [-1250, '−৳12.50'],
    [100_000_000_000, '৳1,00,00,00,000.00'],
    [Number.MAX_SAFE_INTEGER, '৳9,00,71,99,25,47,409.91'], // exact even at the limit
  ])('formats %i poisha in English as %s', (poisha, text) => {
    expect(formatPoisha(poisha, 'en')).toBe(text)
  })

  it('uses Bangla digits and grouping', () => {
    expect(formatPoisha(12500050, 'bn')).toBe('৳১,২৫,০০০.৫০')
  })

  it('shows a plus sign only when asked', () => {
    expect(formatPoisha(500, 'en', { signed: true })).toBe('+৳5.00')
    expect(formatPoisha(0, 'en', { signed: true })).toBe('৳0.00')
    expect(formatPoisha(500, 'en', { bare: true })).toBe('5.00')
  })

  it('refuses non-integers instead of rounding money', () => {
    expect(() => formatPoisha(120.5, 'en')).toThrow(RangeError)
    expect(() => formatPoisha(Number.MAX_SAFE_INTEGER + 2, 'en')).toThrow(RangeError)
  })
})

describe('formatBasisPoints', () => {
  it.each([
    [1250, '+12.5%'],
    [-2500, '−25.0%'],
    [0, '0.0%'],
    [3333, '+33.3%'],
    [-6667, '−66.7%'],
    [5, '+0.1%'], // half away from zero
  ])('%i bp → %s', (bp, text) => {
    expect(formatBasisPoints(bp, 'en')).toBe(text)
  })

  it('localises digits', () => {
    expect(formatBasisPoints(1250, 'bn')).toBe('+১২.৫%')
  })
})

describe('checkTakaInput (mirrors backend parse_taka)', () => {
  it.each([
    ['120', '120'],
    ['120.5', '120.5'],
    [' 1,250.75 ', '1,250.75'],
    ['12,50,000', '12,50,000'],
    ['১২০.৫০', '120.50'],
  ])('accepts %s', (input, normalised) => {
    expect(checkTakaInput(input)).toEqual({ ok: true, text: normalised })
  })

  it.each(['', 'abc', '-5', '1.234', '12,50', '1e3', '.5', '5.'])('rejects the format of %s', (input) => {
    expect(checkTakaInput(input)).toEqual({ ok: false, reason: 'format' })
  })

  it.each(['0', '0.00', '1000000000.01'])('rejects out-of-range %s', (input) => {
    expect(checkTakaInput(input)).toEqual({ ok: false, reason: 'range' })
  })

  it('accepts exactly the maximum', () => {
    expect(checkTakaInput('1000000000').ok).toBe(true)
  })
})

describe('poishaToInputText', () => {
  it('produces plain editable text without float artefacts', () => {
    expect(poishaToInputText(12050)).toBe('120.50')
    expect(poishaToInputText(1)).toBe('0.01')
    expect(poishaToInputText(29)).toBe('0.29') // 0.29 * 100 is 28.999… in floats
  })
})

describe('parseSignedTaka (opening balances)', () => {
  it.each([
    ['0', 0],
    ['1250.5', 125050],
    ['-1,250.75', -125075],
    ['−৫০০', -50000],
    ['12,50,000', 125000000],
    ['100000000000', 10_000_000_000_000],
  ])('%s → %i poisha', (input, poisha) => {
    expect(parseSignedTaka(input)).toBe(poisha)
  })

  it.each(['', 'abc', '1.234', '12,50', '--5', '100000000000.01'])('rejects %s', (input) => {
    expect(parseSignedTaka(input)).toBeNull()
  })
})
