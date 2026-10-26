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
      :type="revealed ? 'text' : 'password'"
      autocomplete="off"
      :class="styles.control.input"
      :value="control.data"
      :disabled="!control.enabled"
      :autofocus="appliedOptions.focus"
      :placeholder="appliedOptions.placeholder"
      @change="onChange"
      @focus="isFocused = true"
      @blur="isFocused = false"
    >
    <button
      type="button"
      class="secret-toggle"
      :aria-pressed="revealed"
      :aria-controls="control.id + '-input'"
      @click="revealed = !revealed"
    >
      {{ revealed ? 'Hide' : 'Show' }}
    </button>
  </ControlWrapper>
</template>

<script>
import { and, isStringControl, or, rankWith, schemaMatches } from '@jsonforms/core'
import { rendererProps, useJsonFormsControl } from '@jsonforms/vue'
import { ControlWrapper, useVanillaControl } from '@jsonforms/vue-vanilla'
import { ref } from 'vue'

const SECRET_PROPERTY = /(password|secret|secretKey|accessKey|token)$/i

/* Masks credentials, marked either with "format": "password" or by a credential-like property name */
export const tester = rankWith(2, and(
  isStringControl,
  or(
    schemaMatches(schema => schema.format === 'password'),
    uischema => SECRET_PROPERTY.test(uischema.scope?.split('/').pop() ?? '')
  )
))

export default {
  name: 'SecretStringRenderer',
  components: { ControlWrapper },
  props: rendererProps(),
  setup(props) {
    const revealed = ref(false)

    return {
      ...useVanillaControl(useJsonFormsControl(props), target => target.value || undefined),
      revealed
    }
  }
}
</script>

<!--suppress CssInvalidAtRule -->
<style scoped>
.secret-toggle {
  @apply self-center h-8 rounded-md px-3 text-sm bg-gray-200 text-gray-900 hover:bg-gray-300 dark:bg-gray-700 dark:text-white dark:hover:bg-gray-600 <sm:mt-2 <sm:self-start;
}
</style>
