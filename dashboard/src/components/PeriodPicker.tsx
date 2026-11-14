import { useId, useState } from 'react'
import { useI18n } from '../i18n/context'
import { PRESETS, isIsoDate } from '../lib/dates'
import type { DateRange, PresetId } from '../lib/dates'
import type { PeriodChoice } from './period'

interface Props {
  choice: PeriodChoice
  range: DateRange
  onChange: (choice: PeriodChoice) => void
}

export function PeriodPicker({ choice, range, onChange }: Props) {
  const { t } = useI18n()
  const id = useId()
  const [draft, setDraft] = useState<DateRange>(range)
  const invalid = !isIsoDate(draft.from) || !isIsoDate(draft.to) || draft.from > draft.to

  return (
    <fieldset className="period">
      <legend className="visually-hidden">{t('period')}</legend>
      <label htmlFor={`${id}-preset`}>{t('period')}</label>
      <select
        id={`${id}-preset`}
        value={choice.preset}
        onChange={(e) => {
          const value = e.target.value
          if (value === 'custom') {
            setDraft(range)
            onChange({ preset: 'custom', range })
          } else {
            onChange({ preset: value as PresetId })
          }
        }}
      >
        {PRESETS.map((p) => (
          <option key={p} value={p}>
            {t(p)}
          </option>
        ))}
        <option value="custom">{t('custom')}</option>
      </select>
      {choice.preset === 'custom' ? (
        <>
          <label htmlFor={`${id}-from`}>{t('from')}</label>
          <input
            id={`${id}-from`}
            type="date"
            value={draft.from}
            aria-invalid={invalid}
            onChange={(e) => {
              const next = { ...draft, from: e.target.value }
              setDraft(next)
              if (isIsoDate(next.from) && isIsoDate(next.to) && next.from <= next.to) {
                onChange({ preset: 'custom', range: next })
              }
            }}
          />
          <label htmlFor={`${id}-to`}>{t('to')}</label>
          <input
            id={`${id}-to`}
            type="date"
            value={draft.to}
            aria-invalid={invalid}
            onChange={(e) => {
              const next = { ...draft, to: e.target.value }
              setDraft(next)
              if (isIsoDate(next.from) && isIsoDate(next.to) && next.from <= next.to) {
                onChange({ preset: 'custom', range: next })
              }
            }}
          />
          {invalid ? (
            <span className="field-error" role="alert">
              {t('invalidRange')}
            </span>
          ) : null}
        </>
      ) : null}
    </fieldset>
  )
}
