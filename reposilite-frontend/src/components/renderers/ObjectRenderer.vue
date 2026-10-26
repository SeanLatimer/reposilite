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
    :class="{ 'object-panel': !isRoot }"
  >
    <template v-if="!isRoot">
      <div class="label object-label">
        {{ control.label }}
      </div>
      <div
        v-if="control.description"
        class="description"
      >
        {{ control.description }}
      </div>
    </template>
    <DispatchRenderer
      :visible="control.visible"
      :enabled="control.enabled"
      :schema="control.schema"
      :uischema="detailUiSchema"
      :path="control.path"
      :renderers="control.renderers"
      :cells="control.cells"
    />
  </div>
</template>

<script>
import {findUISchema, isObjectControl, rankWith} from '@jsonforms/core'
import {DispatchRenderer, rendererProps, useJsonFormsControlWithDetail} from '@jsonforms/vue'
import {useVanillaControl} from '@jsonforms/vue-vanilla'
import {useNested} from './util'
import isEmpty from 'lodash/isEmpty'

export const tester = rankWith(2, isObjectControl)

export default {
  name: 'ObjectRenderer',
  components: {
    DispatchRenderer
  },
  props: rendererProps(),
  setup: props => {
    const control = useVanillaControl(useJsonFormsControlWithDetail(props))
    const nested = useNested('object')
    return {
      ...control,
      nested,
    }
  },
  computed: {
    isRoot() {
      return isEmpty(this.control.path)
    },
    detailUiSchema() {
      const result = findUISchema(
          this.control.uischemas,
          this.control.schema,
          this.control.uischema.scope,
          this.control.path,
          'Group',
          this.control.uischema,
          this.control.rootSchema
      )
      if (this.isRoot) {
        result.type = 'VerticalLayout'
      } else {
        // label and description are rendered above the group
        if (this.nested.level > 0) {
          result.options = {
            ...result.options,
            bare: true,
            alignLeft:
                this.nested.level >= 4 || this.nested.parentElement === 'array',
          }
        }
      }
      return result
    }
  }
}
</script>

<!--suppress CssInvalidAtRule -->
<style scoped>
.object-panel {
  @apply rounded-lg bg-gray-100 dark:bg-gray-800 px-4 py-3;
}
.object-label {
  padding-left: 0;
}
</style>
