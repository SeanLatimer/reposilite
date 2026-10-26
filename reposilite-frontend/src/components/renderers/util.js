/*
 * Copyright (c) 2020-2026 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {inject, provide} from 'vue'

export const useNested = (element) => {
  const nestedInfo = inject('jsonforms.nestedInfo', { level: 0 })
  if (element) {
    provide('jsonforms.nestedInfo', {
      level: nestedInfo.level + 1,
      parentElement: element,
    })
  }
  return nestedInfo
}

const DURATION_UNITS = [
  ['day', 60 * 60 * 24],
  ['hour', 60 * 60],
  ['minute', 60],
  ['second', 1],
]

/* Formats a number of seconds using its two largest units, e.g. 172800 -> "2 days", 5400 -> "1 hour 30 minutes" */
export const formatSeconds = (seconds) => {
  if (!Number.isInteger(seconds) || seconds <= 0) return undefined
  const parts = []
  let remaining = seconds
  for (const [unit, size] of DURATION_UNITS) {
    const amount = Math.floor(remaining / size)
    if (amount > 0) {
      parts.push(`${amount} ${unit}${amount === 1 ? '' : 's'}`)
      remaining -= amount * size
    }
    if (parts.length === 2) break
  }
  return parts.join(' ')
}
