<!--
  ~ Copyright (c) 2020-2026 dzikoysk
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<template>
  <div
    v-if="control.visible"
    class="string-array array-list-shell"
  >
    <h2
      :id="`${control.id}-label`"
      class="array-list-label"
    >
      {{ control.label }}
    </h2>
    <div
      v-if="control.description"
      class="description"
    >
      {{ control.description }}
    </div>
    <ul
      class="string-array-items"
      :aria-labelledby="`${control.id}-label`"
    >
      <li
        v-for="(value, index) in items"
        :key="`${control.path}-${index}`"
        class="string-array-row"
      >
        <input
          :id="`${control.id}-item-${index}`"
          type="text"
          class="string-array-input"
          :value="value"
          :disabled="!control.enabled"
          :aria-label="`${control.label} ${index + 1}`"
          :aria-invalid="!!itemErrors[index]"
          @change="updateItem(index, $event.target.value)"
        >
        <button
          type="button"
          class="string-array-remove"
          :disabled="!control.enabled"
          :aria-label="`Remove ${value || 'entry'}`"
          title="Remove"
          @click="removeItem(index)"
        >
          ×
        </button>
        <div
          v-if="itemErrors[index]"
          class="error string-array-error"
        >
          {{ itemErrors[index] }}
        </div>
      </li>
      <li
        v-if="items.length === 0"
        class="string-array-empty"
      >
        No entries
      </li>
    </ul>
    <form
      class="string-array-row"
      @submit.prevent="addDraft"
    >
      <input
        v-model="draft"
        type="text"
        class="string-array-input"
        :disabled="!control.enabled"
        :aria-label="`New ${control.label} entry`"
        placeholder="Add entry and press Enter"
      >
      <button
        type="submit"
        class="string-array-add"
        :disabled="!control.enabled || draft.trim().length === 0"
        :aria-label="`Add ${control.label} entry`"
        title="Add"
      >
        +
      </button>
    </form>
    <div
      v-if="control.errors"
      class="error"
    >
      {{ control.errors }}
    </div>
  </div>
</template>

<script>
import { and, rankWith, schemaMatches, schemaTypeIs, uiTypeIs } from '@jsonforms/core'
import { rendererProps, useJsonFormsControl } from '@jsonforms/vue'
import { computed, inject, ref } from 'vue'

/* Arrays of plain strings (no enum, no objects) are edited as a compact list instead of one tab per entry */
export const tester = rankWith(3, and(
  uiTypeIs('Control'),
  schemaTypeIs('array'),
  schemaMatches(schema => schema.items?.type === 'string' && !schema.items.enum)
))

export default {
  name: 'StringArrayRenderer',
  props: rendererProps(),
  setup(props) {
    const { control, handleChange } = useJsonFormsControl(props)
    const jsonforms = inject('jsonforms')
    const draft = ref('')

    const items = computed(() => control.value.data ?? [])
    const instancePath = computed(() => '/' + control.value.path.replaceAll('.', '/'))

    const itemErrors = computed(() => {
      const errors = {}
      jsonforms?.core?.errors?.forEach(error => {
        const prefix = `${instancePath.value}/`
        if (!error.instancePath?.startsWith(prefix)) return
        const index = Number(error.instancePath.substring(prefix.length))
        if (Number.isInteger(index)) errors[index] = error.message
      })
      return errors
    })

    const commit = (values) => handleChange(control.value.path, values)

    /* An empty entry would match everything, so clearing a row removes it */
    const updateItem = (index, value) => {
      const trimmed = value.trim()
      const values = [...items.value]
      if (trimmed.length === 0) values.splice(index, 1)
      else values[index] = trimmed
      commit(values)
    }

    const removeItem = (index) =>
      commit(items.value.filter((_, current) => current !== index))

    const addDraft = () => {
      const value = draft.value.trim()
      if (value.length === 0) return
      commit([...items.value, value])
      draft.value = ''
    }

    return {
      control,
      draft,
      items,
      itemErrors,
      updateItem,
      removeItem,
      addDraft
    }
  }
}
</script>

<!--suppress CssInvalidAtRule -->
<style scoped>
.string-array-items {
  @apply flex flex-col gap-2 mt-3 mb-2;
}
.string-array-row {
  @apply flex flex-wrap items-center gap-2;
}
.string-array-input {
  @apply h-9 w-full max-w-md rounded border border-gray-300 bg-white px-3 text-sm text-black outline-none dark:border-gray-700 dark:bg-gray-900 dark:text-white;
}
.string-array-input:focus {
  @apply border-blue-500;
}
.string-array-input[aria-invalid="true"] {
  @apply border-red-500;
}
.string-array-input::placeholder {
  @apply text-gray-400 dark:text-gray-500;
}
.string-array-remove,
.string-array-add {
  @apply h-7 w-7 flex-none rounded-full leading-7 cursor-pointer;
}
.string-array-remove {
  @apply text-lg text-red-500 hover:bg-red-100 hover:text-red-600 dark:text-red-400 dark:hover:bg-red-900 dark:hover:bg-opacity-40 dark:hover:text-red-300;
}
.string-array-add {
  @apply bg-blue-700 text-white hover:bg-blue-800 dark:hover:bg-blue-600;
}
.string-array-add:disabled,
.string-array-remove:disabled {
  @apply cursor-not-allowed opacity-50;
}
.string-array-error {
  @apply w-full px-0 text-sm;
}
.string-array-empty {
  @apply text-sm italic text-gray-500 dark:text-gray-400;
}
</style>
