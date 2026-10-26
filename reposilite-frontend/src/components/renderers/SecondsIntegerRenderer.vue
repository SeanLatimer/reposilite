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
  <ControlWrapper
    v-bind="controlWrapper"
    :styles="styles"
    :is-focused="isFocused"
    :applied-options="appliedOptions"
  >
    <input
      :id="control.id + '-input'"
      type="number"
      :step="1"
      :class="styles.control.input"
      :value="control.data"
      :disabled="!control.enabled"
      :autofocus="appliedOptions.focus"
      :placeholder="appliedOptions.placeholder"
      :aria-describedby="duration ? `${control.id}-duration` : undefined"
      @change="onChange"
      @focus="isFocused = true"
      @blur="isFocused = false"
    >
    <span
      v-if="duration"
      :id="`${control.id}-duration`"
      class="duration-hint"
    >
      = {{ duration }}
    </span>
  </ControlWrapper>
</template>

<script>
import { and, isIntegerControl, rankWith, schemaMatches } from '@jsonforms/core'
import { rendererProps, useJsonFormsControl } from '@jsonforms/vue'
import { ControlWrapper, useVanillaControl } from '@jsonforms/vue-vanilla'
import { computed } from 'vue'
import { formatSeconds } from './util'

/* Integer fields documented in seconds get a human-readable hint, e.g. 172800 = 2 days */
export const tester = rankWith(2, and(
  isIntegerControl,
  schemaMatches(schema => /\bseconds\b/i.test(`${schema.title ?? ''} ${schema.description ?? ''}`))
))

export default {
  name: 'SecondsIntegerRenderer',
  components: { ControlWrapper },
  props: rendererProps(),
  setup(props) {
    const control = useVanillaControl(
      useJsonFormsControl(props),
      target => target.value === '' ? undefined : parseInt(target.value, 10)
    )
    const duration = computed(() => formatSeconds(control.control.value.data))

    return {
      ...control,
      duration
    }
  }
}
</script>

<!--suppress CssInvalidAtRule -->
<style scoped>
.duration-hint {
  @apply self-center whitespace-nowrap px-2 text-sm text-gray-600 dark:text-gray-300;
}
</style>
